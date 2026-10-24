$ErrorActionPreference = 'Stop'
$JavaExe = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $JavaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $Candidates = @(
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\java.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr\bin\java.exe')
    )
    $JavaExe = $Candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if (-not $JavaExe) {
        $Found = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($Found) { $JavaExe = $Found.Source }
    }
}
if (-not $JavaExe) { throw 'JDK not found. Install Android Studio or JDK 17/21.' }
$Line = (& $JavaExe -version 2>&1 | Select-String 'version "([^"]+)"' | Select-Object -First 1)
if (-not $Line) { throw "Cannot read Java version from $JavaExe" }
$Version = $Line.Matches.Groups[1].Value
$MajorText = ($Version -split '\.')[0]
if ($MajorText -eq '1') { $MajorText = ($Version -split '\.')[1] }
$Major = [int]$MajorText
if ($Major -lt 17 -or $Major -gt 24) { throw "Unsupported Java $Version. Use JDK 17 or 21." }
$Sdk = $env:ANDROID_SDK_ROOT
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path (Join-Path $Sdk 'platforms\android-36\android.jar'))) {
    throw "Android SDK Platform 36 not found at $Sdk. Install it from Android Studio SDK Manager."
}
$ToolsRoot = Join-Path $Sdk 'build-tools'
$Tools = Get-ChildItem -LiteralPath $ToolsRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { (Test-Path (Join-Path $_.FullName 'aapt2.exe')) -and (Test-Path (Join-Path $_.FullName 'zipalign.exe')) } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $Tools) { throw "Android SDK Build-Tools not found under $ToolsRoot." }
Write-Host "Java: $Version"
Write-Host "Android SDK: $Sdk"
Write-Host "Build-Tools: $($Tools.Name)"
Write-Host 'Environment checks passed.'
