param(
    [Parameter(Mandatory = $true)]
    [string]$ResultFile
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Candidates = New-Object 'System.Collections.Generic.List[object]'
$SeenHomes = @{}

function Add-JavaCandidate {
    param([string]$Path, [string]$Source)

    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $Path = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        if ([IO.Path]::GetFileName($Path) -ieq 'java.exe') {
            $Path = Split-Path -Parent (Split-Path -Parent $Path)
        } elseif ([IO.Path]::GetFileName($Path) -ieq 'javac.exe') {
            $Path = Split-Path -Parent (Split-Path -Parent $Path)
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'bin\java.exe'))) { return }
    try { $Key = [IO.Path]::GetFullPath($Path).TrimEnd('\').ToLowerInvariant() } catch { $Key = $Path.ToLowerInvariant() }
    if ($SeenHomes.ContainsKey($Key)) { return }
    $SeenHomes[$Key] = $true
    $Candidates.Add([pscustomobject]@{ Home = $Path; Source = $Source }) | Out-Null
}

function Add-PatternCandidates {
    param([string]$Pattern, [string]$Source)
    if ([string]::IsNullOrWhiteSpace($Pattern)) { return }
    Get-ChildItem -Path $Pattern -Directory -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        ForEach-Object { Add-JavaCandidate -Path $_.FullName -Source $Source }
}

function Get-CompatibleJava {
    param([string]$Home, [string]$Source)
    $Java = Join-Path $Home 'bin\java.exe'
    $Javac = Join-Path $Home 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $Java)) { return $null }
    if (-not (Test-Path -LiteralPath $Javac)) {
        Write-Host "  Rejected $Home - javac.exe is missing (this is not a full JDK)."
        return $null
    }
    $VersionOutput = (& $Java -version 2>&1 | Out-String)
    $VersionMatch = [regex]::Match($VersionOutput, 'version\s+"([^"]+)"')
    if ($VersionMatch.Success) {
        $Version = $VersionMatch.Groups[1].Value
    } else {
        $VersionMatch = [regex]::Match($VersionOutput, '(?m)^\s*openjdk\s+(\d+)(?:[.\s]|$)')
        if (-not $VersionMatch.Success) {
            Write-Host "  Rejected $Home - could not determine Java version."
            return $null
        }
        $Version = $VersionMatch.Groups[1].Value
    }
    $MajorText = ($Version -split '[._+\-]')[0]
    if ($MajorText -eq '1') { $MajorText = ($Version -split '[._+\-]')[1] }
    $Major = 0
    if (-not [int]::TryParse($MajorText, [ref]$Major)) {
        Write-Host "  Rejected $Home - unrecognized Java version $Version."
        return $null
    }
    if ($Major -lt 17 -or $Major -gt 24) {
        Write-Host "  Rejected Java $Version at $Home; this Gradle/Android build supports JDK 17-24."
        return $null
    }
    Write-Host "  Selected JDK $Version from $Source - $Home"
    return [pscustomobject]@{ Home = $Home; Version = $Version }
}

Write-Host 'Clear GPS: locating a compatible JDK (17-24)...'

# Keep JAVA_HOME first, but do not trust it blindly: an old JDK must not block fallback detection.
Add-JavaCandidate -Path $env:JAVA_HOME -Source 'JAVA_HOME'

# Android Studio / JetBrains Runtime.
$ProgramFilesRoot = $env:ProgramFiles
$LocalPrograms = Join-Path $env:LOCALAPPDATA 'Programs'
@(
    (Join-Path $ProgramFilesRoot 'Android\Android Studio\jbr'),
    (Join-Path $ProgramFilesRoot 'Android\Android Studio\jre'),
    (Join-Path $LocalPrograms 'Android Studio\jbr'),
    (Join-Path $LocalPrograms 'Android Studio\jre'),
    (Join-Path $env:LOCALAPPDATA 'Android\Studio\jbr'),
    (Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps\AndroidStudio\*\*\jbr'),
    (Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps\AndroidStudio\*\*\*\jbr')
) | ForEach-Object {
    if ($_ -match '[*?]') { Add-PatternCandidates -Pattern $_ -Source 'Android Studio / JetBrains Toolbox' }
    else { Add-JavaCandidate -Path $_ -Source 'Android Studio / JetBrains Runtime' }
}

# Common JDK vendor installation folders; no administrator rights are needed to use these.
@(
    (Join-Path $ProgramFilesRoot 'Eclipse Adoptium\jdk-*'),
    (Join-Path $ProgramFilesRoot 'Microsoft\jdk-*'),
    (Join-Path $ProgramFilesRoot 'Java\jdk*'),
    (Join-Path $ProgramFilesRoot 'Amazon Corretto\jdk*'),
    (Join-Path $ProgramFilesRoot 'Zulu\zulu*'),
    (Join-Path $ProgramFilesRoot 'BellSoft\LibericaJDK*'),
    (Join-Path $ProgramFilesRoot 'Semeru\jdk-*'),
    (Join-Path $env:USERPROFILE '.jdks\*'),
    (Join-Path $env:USERPROFILE '.gradle\jdks\*'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium\jdk-*'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Microsoft\jdk-*'),
    (Join-Path $env:LOCALAPPDATA 'ClearGPS\tools\jdk-21')
) | ForEach-Object { Add-PatternCandidates -Pattern $_ -Source 'installed JDK folder' }

# Registry entries used by some Java distributions.
foreach ($RegistryRoot in @(
    'HKLM:\SOFTWARE\JavaSoft\JDK',
    'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
    'HKLM:\SOFTWARE\Eclipse Adoptium\JDK',
    'HKCU:\SOFTWARE\JavaSoft\JDK',
    'HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK'
)) {
    if (Test-Path $RegistryRoot) {
        Get-ChildItem -Path $RegistryRoot -ErrorAction SilentlyContinue | ForEach-Object {
            $RegistryItem = Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue
            if ($RegistryItem.JavaHome) { Add-JavaCandidate -Path $RegistryItem.JavaHome -Source 'Windows registry' }
        }
        $RootItem = Get-ItemProperty -LiteralPath $RegistryRoot -ErrorAction SilentlyContinue
        if ($RootItem.JavaHome) { Add-JavaCandidate -Path $RootItem.JavaHome -Source 'Windows registry' }
    }
}

# PATH is deliberately checked after known full JDK installations, because it may point to Java 8.
Get-Command java.exe -All -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Source) { Add-JavaCandidate -Path $_.Source -Source 'PATH' }
}

foreach ($Candidate in $Candidates) {
    $Valid = Get-CompatibleJava -Home $Candidate.Home -Source $Candidate.Source
    if ($Valid) {
        [IO.Directory]::CreateDirectory((Split-Path -Parent $ResultFile)) | Out-Null
        [IO.File]::WriteAllText($ResultFile, $Valid.Home, [System.Text.UTF8Encoding]::new($false))
        exit 0
    }
}

# If no compatible JDK is installed, fetch a user-local Eclipse Temurin JDK 21.
$InstallDir = Join-Path $env:LOCALAPPDATA 'ClearGPS\tools\jdk-21'
if (Test-Path -LiteralPath (Join-Path $InstallDir 'bin\java.exe')) {
    $Valid = Get-CompatibleJava -Home $InstallDir -Source 'Clear GPS managed JDK'
    if ($Valid) {
        [IO.File]::WriteAllText($ResultFile, $Valid.Home, [System.Text.UTF8Encoding]::new($false))
        exit 0
    }
}

Write-Host 'No compatible JDK was found. Trying an automatic, checksum-verified Temurin JDK 21 download...'
$ZipPath = Join-Path $env:TEMP ("cleargps-temurin21-" + [guid]::NewGuid().ToString('N') + '.zip')
$ExtractRoot = Join-Path $env:TEMP ("cleargps-temurin21-" + [guid]::NewGuid().ToString('N'))
try {
    $Api = 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $Assets = Invoke-RestMethod -Uri $Api -Method Get
    $Asset = @($Assets | Where-Object { $_.binary.package.link -and $_.binary.package.checksum } | Select-Object -First 1)[0]
    if (-not $Asset) { throw 'Adoptium API did not return a downloadable JDK package with a SHA-256 checksum.' }
    $PackageUrl = [string]$Asset.binary.package.link
    $ExpectedHash = ([string]$Asset.binary.package.checksum).ToLowerInvariant()
    Write-Host 'Downloading JDK 21 from Eclipse Adoptium. The archive will be checked against its published SHA-256 checksum.'
    Invoke-WebRequest -Uri $PackageUrl -OutFile $ZipPath -UseBasicParsing
    $ActualHash = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ActualHash -ne $ExpectedHash) { throw "JDK archive SHA-256 mismatch; download rejected. Expected $ExpectedHash, got $ActualHash." }
    if (Test-Path -LiteralPath $ExtractRoot) { Remove-Item -LiteralPath $ExtractRoot -Recurse -Force }
    New-Item -ItemType Directory -Path $ExtractRoot -Force | Out-Null
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractRoot -Force
    $JdkFolder = Get-ChildItem -LiteralPath $ExtractRoot -Directory |
        Where-Object { (Test-Path (Join-Path $_.FullName 'bin\java.exe')) -and (Test-Path (Join-Path $_.FullName 'bin\javac.exe')) } |
        Select-Object -First 1
    if (-not $JdkFolder) { throw 'Downloaded archive did not contain the expected JDK executables.' }
    $InstallParent = Split-Path -Parent $InstallDir
    New-Item -ItemType Directory -Path $InstallParent -Force | Out-Null
    if (Test-Path -LiteralPath $InstallDir) { Remove-Item -LiteralPath $InstallDir -Recurse -Force }
    Move-Item -LiteralPath $JdkFolder.FullName -Destination $InstallDir
    $Valid = Get-CompatibleJava -Home $InstallDir -Source 'automatically downloaded Eclipse Temurin'
    if (-not $Valid) { throw 'The downloaded JDK did not pass compatibility checks.' }
    [IO.File]::WriteAllText($ResultFile, $Valid.Home, [System.Text.UTF8Encoding]::new($false))
    Write-Host 'JDK setup finished successfully.'
    exit 0
} catch {
    Write-Error ("Could not find or download a compatible JDK. Check Internet access and try again. Details: " + $_.Exception.Message)
    exit 1
} finally {
    if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $ExtractRoot) { Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
