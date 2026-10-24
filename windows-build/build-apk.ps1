$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-SetupHelper {
    param([string]$ScriptPath)

    $ResultFile = Join-Path $env:TEMP ("cleargps-setup-" + [guid]::NewGuid().ToString('N') + '.txt')
    try {
        & (Join-Path $PSHOME 'powershell.exe') -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath -ResultFile $ResultFile
        if ($LASTEXITCODE -ne 0) { throw "Setup helper failed: $([IO.Path]::GetFileName($ScriptPath)) (exit code $LASTEXITCODE)." }
        if (-not (Test-Path -LiteralPath $ResultFile)) { throw "Setup helper did not return a path: $([IO.Path]::GetFileName($ScriptPath))." }
        return [IO.File]::ReadAllText($ResultFile, [System.Text.Encoding]::UTF8).Trim()
    } finally {
        if (Test-Path -LiteralPath $ResultFile) { Remove-Item -LiteralPath $ResultFile -Force -ErrorAction SilentlyContinue }
    }
}

$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $ProjectRoot

try {
    Write-Host ''
    Write-Host '========== Clear GPS Android build =========='
    Write-Host "Project: $ProjectRoot"
    Write-Host ''

    $ResolvedJava = Invoke-SetupHelper -ScriptPath (Join-Path $PSScriptRoot 'ensure-java.ps1')
    if (-not (Test-Path -LiteralPath (Join-Path $ResolvedJava 'bin\java.exe'))) {
        throw "JDK resolver returned an invalid path: $ResolvedJava"
    }
    $env:JAVA_HOME = $ResolvedJava
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    Write-Host "Using JAVA_HOME=$env:JAVA_HOME"

    $ResolvedSdk = Invoke-SetupHelper -ScriptPath (Join-Path $PSScriptRoot 'ensure-android-sdk.ps1')
    if (-not (Test-Path -LiteralPath (Join-Path $ResolvedSdk 'platforms\android-36\android.jar'))) {
        throw "Android SDK resolver returned an incomplete SDK: $ResolvedSdk"
    }
    $env:ANDROID_SDK_ROOT = $ResolvedSdk
    $env:ANDROID_HOME = $ResolvedSdk
    Write-Host "Using Android SDK: $env:ANDROID_SDK_ROOT"
    Write-Host ''

    & (Join-Path $PSScriptRoot 'check-environment.ps1')
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "Environment validation failed with exit code $LASTEXITCODE." }

    Write-Host ''
    Write-Host 'Running unit tests and building the debug APK...'
    $GradleWrapper = Join-Path $ProjectRoot 'gradlew.bat'
    & $GradleWrapper --no-daemon --stacktrace testDebugUnitTest assembleDebug
    $GradleExitCode = $LASTEXITCODE
    if ($GradleExitCode -ne 0) {
        throw "Gradle failed with exit code $GradleExitCode. The first compiler/dependency error above is the most useful diagnostic."
    }

    $ApkSource = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $ApkSource)) { throw "Gradle reported success, but the APK was not found at $ApkSource." }
    $Dist = Join-Path $ProjectRoot 'dist'
    New-Item -ItemType Directory -Path $Dist -Force | Out-Null
    $ApkDestination = Join-Path $Dist 'ClearGPS-debug.apk'
    Copy-Item -LiteralPath $ApkSource -Destination $ApkDestination -Force
    Write-Host ''
    Write-Host 'BUILD SUCCEEDED'
    Write-Host "APK: $ApkDestination"
    Write-Host "Size: $([math]::Round((Get-Item -LiteralPath $ApkDestination).Length / 1MB, 2)) MiB"
    exit 0
} catch {
    Write-Host ''
    Write-Host 'BUILD FAILED'
    Write-Error $_
    Write-Host 'No source files were changed by the build script. Check network access, disk space, and the first detailed error above.'
    exit 1
}
