param(
    [Parameter(Mandatory = $true)]
    [string]$ResultFile
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

function Test-SdkComplete {
    param([string]$Root)
    if (-not (Test-Path -LiteralPath (Join-Path $Root 'platforms\android-36\android.jar'))) { return $false }
    $BuildToolsRoot = Join-Path $Root 'build-tools'
    $BuildTools = Get-ChildItem -LiteralPath $BuildToolsRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object {
            (Test-Path (Join-Path $_.FullName 'aapt2.exe')) -and
            (Test-Path (Join-Path $_.FullName 'zipalign.exe'))
        } | Select-Object -First 1
    return [bool]$BuildTools
}

function Find-SdkManager {
    param([string]$Root)
    $Preferred = Join-Path $Root 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path -LiteralPath $Preferred) { return $Preferred }
    $ToolsRoot = Join-Path $Root 'cmdline-tools'
    $ToolDirs = Get-ChildItem -LiteralPath $ToolsRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending
    foreach ($ToolDir in $ToolDirs) {
        $Possible = Join-Path $ToolDir.FullName 'bin\sdkmanager.bat'
        if (Test-Path -LiteralPath $Possible) { return $Possible }
    }
    $Legacy = Join-Path $Root 'tools\bin\sdkmanager.bat'
    if (Test-Path -LiteralPath $Legacy) { return $Legacy }
    return $null
}

function Add-SdkRoot {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $Expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
    if (-not (Test-Path -LiteralPath $Expanded -PathType Container)) { return }
    try { $Key = [IO.Path]::GetFullPath($Expanded).TrimEnd('\').ToLowerInvariant() } catch { $Key = $Expanded.ToLowerInvariant() }
    if (-not $script:SeenSdkRoots.ContainsKey($Key)) {
        $script:SeenSdkRoots[$Key] = $true
        $script:SdkRoots.Add($Expanded) | Out-Null
    }
}

$script:SdkRoots = New-Object 'System.Collections.Generic.List[string]'
$script:SeenSdkRoots = @{}
Add-SdkRoot -Path $env:ANDROID_SDK_ROOT
Add-SdkRoot -Path $env:ANDROID_HOME
@(
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'),
    (Join-Path $env:ProgramFiles 'Android\Sdk'),
    (Join-Path $env:USERPROFILE 'Android\Sdk'),
    'C:\Android\Sdk',
    (Join-Path $env:LOCALAPPDATA 'ClearGPS\android-sdk')
) | ForEach-Object { Add-SdkRoot -Path $_ }

Write-Host 'Clear GPS: locating Android SDK Platform 36 and Build-Tools...'

foreach ($Root in $script:SdkRoots) {
    if (Test-SdkComplete -Root $Root) {
        Write-Host "  Selected existing Android SDK: $Root"
        [IO.Directory]::CreateDirectory((Split-Path -Parent $ResultFile)) | Out-Null
        [IO.File]::WriteAllText($ResultFile, $Root, [System.Text.UTF8Encoding]::new($false))
        exit 0
    }
}

$SdkRoot = $null
$SdkManager = $null
foreach ($Root in $script:SdkRoots) {
    $Manager = Find-SdkManager -Root $Root
    if ($Manager) {
        $SdkRoot = $Root
        $SdkManager = $Manager
        break
    }
}

if (-not $SdkRoot) {
    # Reuse an existing SDK directory if possible, otherwise install into a user-local managed folder.
    foreach ($Root in $script:SdkRoots) {
        if ((Test-Path -LiteralPath (Join-Path $Root 'platforms')) -or
            (Test-Path -LiteralPath (Join-Path $Root 'build-tools')) -or
            (Test-Path -LiteralPath (Join-Path $Root 'cmdline-tools'))) {
            $SdkRoot = $Root
            break
        }
    }
    if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'ClearGPS\android-sdk' }
    Write-Host ''
    Write-Host 'No complete Android SDK with command-line package manager was found.'
    Write-Host 'The official command-line tools download is about 156 MB. Platform 36 and Build-Tools will be downloaded afterward.'
    Write-Host 'Continuing means you agree to review/accept the Android SDK license agreements: https://developer.android.com/studio/terms'
    $Answer = Read-Host 'Download tools, accept the SDK licenses and install the required components? [Y/N]'
    if ($Answer -notmatch '^(?i)y(es)?$') { throw 'Android SDK setup was cancelled by the user.' }

    $ToolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
    $ExpectedToolsHash = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $ZipPath = Join-Path $env:TEMP ("cleargps-android-tools-" + [guid]::NewGuid().ToString('N') + '.zip')
    $ExtractRoot = Join-Path $env:TEMP ("cleargps-android-tools-" + [guid]::NewGuid().ToString('N'))
    try {
        Write-Host 'Downloading official Android command-line tools...'
        Invoke-WebRequest -Uri $ToolsUrl -OutFile $ZipPath -UseBasicParsing
        $ActualHash = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($ActualHash -ne $ExpectedToolsHash) {
            throw "Android command-line tools SHA-256 mismatch; archive rejected. Expected $ExpectedToolsHash, got $ActualHash."
        }
        New-Item -ItemType Directory -Path $ExtractRoot -Force | Out-Null
        Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractRoot -Force
        $ExtractedTools = Join-Path $ExtractRoot 'cmdline-tools'
        if (-not (Test-Path -LiteralPath (Join-Path $ExtractedTools 'bin\sdkmanager.bat'))) {
            throw 'Official archive did not contain cmdline-tools\bin\sdkmanager.bat.'
        }
        $LatestDir = Join-Path $SdkRoot 'cmdline-tools\latest'
        New-Item -ItemType Directory -Path (Split-Path -Parent $LatestDir) -Force | Out-Null
        if (Test-Path -LiteralPath $LatestDir) { Remove-Item -LiteralPath $LatestDir -Recurse -Force }
        Move-Item -LiteralPath $ExtractedTools -Destination $LatestDir
        $SdkManager = Join-Path $LatestDir 'bin\sdkmanager.bat'
    } finally {
        if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath $ExtractRoot) { Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue }
    }
} else {
    Write-Host "Found Android SDK package manager at: $SdkManager"
    Write-Host 'Required SDK packages are missing; setup will install only the missing Platform 36 / Build-Tools packages.'
    Write-Host 'Continuing means you agree to review/accept the Android SDK license agreements: https://developer.android.com/studio/terms'
    $Answer = Read-Host 'Install required packages and accept the SDK licenses? [Y/N]'
    if ($Answer -notmatch '^(?i)y(es)?$') { throw 'Android SDK package setup was cancelled by the user.' }
}

try {
    $SdkRoot = [IO.Path]::GetFullPath($SdkRoot)
    New-Item -ItemType Directory -Path $SdkRoot -Force | Out-Null
    Write-Host 'Accepting Android SDK licenses after confirmation...'
    $LicenseAnswers = 1..100 | ForEach-Object { 'y' }
    $LicenseAnswers | & $SdkManager "--sdk_root=$SdkRoot" '--licenses' | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager --licenses failed with exit code $LASTEXITCODE." }

    Write-Host 'Installing Android SDK Platform 36 and Build-Tools 36.0.0...'
    & $SdkManager "--sdk_root=$SdkRoot" 'platforms;android-36' 'build-tools;36.0.0' | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager package installation failed with exit code $LASTEXITCODE." }

    if (-not (Test-SdkComplete -Root $SdkRoot)) {
        throw "sdkmanager returned success, but required Android SDK files are still missing under $SdkRoot."
    }
    [IO.File]::WriteAllText($ResultFile, $SdkRoot, [System.Text.UTF8Encoding]::new($false))
    Write-Host "Android SDK setup finished successfully: $SdkRoot"
    exit 0
} catch {
    Write-Error ("Could not install required Android SDK components. Check network access and the official SDK terms. Details: " + $_.Exception.Message)
    exit 1
}
