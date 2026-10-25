$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# Ensure hashing cmdlets are available even when PowerShell module auto-loading is disabled.
Import-Module Microsoft.PowerShell.Utility -ErrorAction Stop
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$GradleVersion = '8.14.6'
$ExpectedSha256 = '7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24'
$InstallRoot = Join-Path $env:USERPROFILE '.gradle\clear-gps'
$InstallDir = Join-Path $InstallRoot "gradle-$GradleVersion"
$ZipPath = Join-Path $env:TEMP "gradle-$GradleVersion-bin.zip"
$ExtractRoot = Join-Path $env:TEMP "clear-gps-gradle-$GradleVersion"

function Test-GradleHome {
    param([string]$GradleHomePath)
    $GradleExe = Join-Path $GradleHomePath 'bin\gradle.bat'
    if (-not (Test-Path -LiteralPath $GradleExe)) { return $false }
    $PreviousErrorActionPreference = $ErrorActionPreference
    try {
        # Gradle is a .bat file. Capture native stderr without PowerShell 5 converting
        # it into a terminating error when ErrorActionPreference is Stop.
        $ErrorActionPreference = 'Continue'
        $VersionOutput = (& $GradleExe --version 2>&1 | Out-String)
        $ExitCode = $LASTEXITCODE
        return ($ExitCode -eq 0 -and $VersionOutput -match "(?m)^\s*Gradle\s+$([regex]::Escape($GradleVersion))\s*$")
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
}

function Get-FallbackGradleHome {
    $GradleHomePaths = New-Object 'System.Collections.Generic.List[string]'
    $Seen = @{}
    foreach ($GradleHomePath in @($env:GRADLE_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($GradleHomePath)) {
            $Full = [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($GradleHomePath.Trim('"')))
            if (-not $Seen.ContainsKey($Full.ToLowerInvariant())) {
                $Seen[$Full.ToLowerInvariant()] = $true
                $GradleHomePaths.Add($Full) | Out-Null
            }
        }
    }

    Get-Command gradle.bat, gradle.cmd, gradle -All -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Source -and (Test-Path -LiteralPath $_.Source -PathType Leaf)) {
            $GradleHomePath = Split-Path -Parent (Split-Path -Parent $_.Source)
            try { $Full = [IO.Path]::GetFullPath($GradleHomePath) } catch { $Full = $GradleHomePath }
            if (-not $Seen.ContainsKey($Full.ToLowerInvariant())) {
                $Seen[$Full.ToLowerInvariant()] = $true
                $GradleHomePaths.Add($Full) | Out-Null
            }
        }
    }

    foreach ($Pattern in @(
        (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$GradleVersion-bin\*"),
        (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$GradleVersion-all\*")
    )) {
        Get-ChildItem -Path $Pattern -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            Get-ChildItem -LiteralPath $_.FullName -Filter 'gradle.bat' -File -Recurse -ErrorAction SilentlyContinue |
                ForEach-Object {
                    $GradleHomePath = Split-Path -Parent (Split-Path -Parent $_.FullName)
                    try { $Full = [IO.Path]::GetFullPath($GradleHomePath) } catch { $Full = $GradleHomePath }
                    if (-not $Seen.ContainsKey($Full.ToLowerInvariant())) {
                        $Seen[$Full.ToLowerInvariant()] = $true
                        $GradleHomePaths.Add($Full) | Out-Null
                    }
                }
        }
    }

    foreach ($GradleHomePath in $GradleHomePaths) {
        if ((Test-Path -LiteralPath (Join-Path $GradleHomePath 'bin\gradle.bat')) -and (Test-GradleHome -GradleHomePath $GradleHomePath)) {
            return $GradleHomePath
        }
    }
    return $null
}

# Reuse a valid managed installation; reject a partial or different Gradle version.
if (Test-Path -LiteralPath (Join-Path $InstallDir 'bin\gradle.bat')) {
    if (Test-GradleHome -GradleHomePath $InstallDir) {
        Write-Host "Using verified Gradle $GradleVersion at $InstallDir"
        exit 0
    }
    Write-Warning "The cached Gradle directory exists but does not run as Gradle $GradleVersion; rebuilding the managed copy."
    Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
$DownloadFailure = $null
try {
    Write-Host "Downloading official Gradle $GradleVersion..."
    $Uri = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
    Invoke-WebRequest -Uri $Uri -OutFile $ZipPath -UseBasicParsing
    $ActualSha256 = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ActualSha256 -ne $ExpectedSha256) {
        throw "SHA-256 mismatch: expected $ExpectedSha256; got $ActualSha256. Archive rejected."
    }
    if (Test-Path -LiteralPath $ExtractRoot) { Remove-Item -LiteralPath $ExtractRoot -Recurse -Force }
    New-Item -ItemType Directory -Path $ExtractRoot | Out-Null
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractRoot -Force
    $Extracted = Join-Path $ExtractRoot "gradle-$GradleVersion"
    if (-not (Test-Path -LiteralPath (Join-Path $Extracted 'bin\gradle.bat'))) {
        throw 'Expected Gradle executable not found in the verified archive.'
    }
    if (Test-Path -LiteralPath $InstallDir) { Remove-Item -LiteralPath $InstallDir -Recurse -Force }
    Move-Item -LiteralPath $Extracted -Destination $InstallDir
    if (-not (Test-GradleHome -GradleHomePath $InstallDir)) {
        throw "Downloaded Gradle did not pass the exact-version launch check."
    }
    Write-Host "Gradle installed and verified under $InstallDir"
    exit 0
} catch {
    $DownloadFailure = $_.Exception.Message
    Write-Warning "Gradle download/setup failed: $DownloadFailure"
} finally {
    if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $ExtractRoot) { Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue }
}

# Network fallback: reuse an exact-version Gradle already installed or cached on this computer.
$FallbackHome = Get-FallbackGradleHome
if ($FallbackHome) {
    try {
        Write-Host "Fallback: found a working Gradle $GradleVersion at $FallbackHome"
        if (Test-Path -LiteralPath $InstallDir) { Remove-Item -LiteralPath $InstallDir -Recurse -Force }
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
        Copy-Item -Path (Join-Path $FallbackHome '*') -Destination $InstallDir -Recurse -Force
        if (Test-GradleHome -GradleHomePath $InstallDir) {
            Write-Host "Fallback Gradle copy is ready at $InstallDir"
            exit 0
        }
        Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    } catch {
        Write-Warning "Gradle cache fallback failed: $($_.Exception.Message)"
    }
}

Write-Error "Could not install Gradle $GradleVersion. Download error: $DownloadFailure. No working copy of the exact version was found in PATH or the standard Gradle cache."
exit 1
