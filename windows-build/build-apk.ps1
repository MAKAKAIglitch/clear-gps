$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-SetupHelper {
    param([string]$ScriptPath)

    $ResultFile = Join-Path $env:TEMP ("cleargps-setup-" + [guid]::NewGuid().ToString('N') + '.txt')
    $PowerShellExecutable = (Get-Process -Id $PID).Path
    try {
        & $PowerShellExecutable -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath -ResultFile $ResultFile | Out-Host
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
    # Size the Gradle heap from installed RAM, while reserving memory for Windows and other apps.
    $OsMemory = Get-CimInstance -ClassName Win32_OperatingSystem
    $TotalRamMb = [math]::Floor([double]$OsMemory.TotalVisibleMemorySize / 1024)
    $FreeRamMb = [math]::Floor([double]$OsMemory.FreePhysicalMemory / 1024)
    if ($TotalRamMb -lt 2048) { throw "At least 2 GiB RAM is recommended for Android builds; detected $TotalRamMb MiB." }
    $ReserveMb = [math]::Max(2048, [math]::Floor($TotalRamMb * 0.25))
    $HeapMb = [math]::Min([math]::Floor($TotalRamMb * 0.60), $TotalRamMb - $ReserveMb)
    # Also cap the heap by currently free RAM; leave at least 1.5 GiB or 25% of free RAM unused.
    $FreeReserveMb = [math]::Max(1536, [math]::Floor($FreeRamMb * 0.25))
    $HeapMb = [math]::Min($HeapMb, [math]::Max(1024, $FreeRamMb - $FreeReserveMb))
    $HeapMb = [math]::Min(8192, [math]::Max(1024, [math]::Floor($HeapMb / 256) * 256))
    $MetaMb = [math]::Min(1024, [math]::Max(512, [math]::Floor($HeapMb * 0.20)))
    $GradleJvmArgs = '-Xms256m -Xmx' + $HeapMb + 'm -XX:MaxMetaspaceSize=' + $MetaMb + 'm -Dfile.encoding=UTF-8'
    Write-Host ("System RAM: {0} MiB total, {1} MiB free; Gradle heap: {2} MiB" -f $TotalRamMb, $FreeRamMb, $HeapMb)
    $GradleWrapper = Join-Path $ProjectRoot 'gradlew.bat'
    $BuildLogRoot = Join-Path $env:LOCALAPPDATA 'ClearGPS\logs'
    New-Item -ItemType Directory -Path $BuildLogRoot -Force | Out-Null
    $BuildLog = Join-Path $BuildLogRoot ("build-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".log")
    Write-Host "Build log: $BuildLog"

    function Invoke-LoggedGradle {
        param(
            [string[]]$GradleArguments,
            [string]$LogPath
        )

        $PreviousErrorActionPreference = $ErrorActionPreference
        $PreviousJvmArgsOverride = $env:CLEARGPS_GRADLE_JVM_ARGS
        try {
            # Keep arguments space-free across the .bat -> PowerShell -> .bat boundary.
            # The runner applies the JVM arguments through GRADLE_OPTS instead of
            # forwarding a quoted -D property through several command-line parsers.
            $ErrorActionPreference = 'Continue'
            $env:CLEARGPS_GRADLE_JVM_ARGS = $GradleJvmArgs
            & $GradleWrapper @GradleArguments 2>&1 |
                Tee-Object -FilePath $LogPath | Out-Host
            return [int]$LASTEXITCODE
        } finally {
            $ErrorActionPreference = $PreviousErrorActionPreference
            if ($null -eq $PreviousJvmArgsOverride) {
                Remove-Item Env:CLEARGPS_GRADLE_JVM_ARGS -ErrorAction SilentlyContinue
            } else {
                $env:CLEARGPS_GRADLE_JVM_ARGS = $PreviousJvmArgsOverride
            }
        }
    }

    $GradleExitCode = Invoke-LoggedGradle -GradleArguments @('--stacktrace', '--build-cache', ':core-detect:test', 'testDebugUnitTest', 'assembleDebug') -LogPath $BuildLog
    if ($GradleExitCode -ne 0) {
        $LogText = Get-Content -LiteralPath $BuildLog -Raw -ErrorAction SilentlyContinue
        $RetryPattern = '(?i)(UnknownHostException|Unknown host|Could not resolve host|Could not GET|Could not HEAD|Connection timed out|Read timed out|Connection reset|Connection refused|TLS handshake|PKIX path building failed|503 Service Unavailable|502 Bad Gateway|429 Too Many Requests|Temporary failure in name resolution|Network is unreachable|No route to host)'
        if ($LogText -match $RetryPattern) {
            $RetryLog = Join-Path $BuildLogRoot (([IO.Path]::GetFileNameWithoutExtension($BuildLog)) + '-retry.log')
            Write-Host ''
            Write-Host 'A network/dependency-server error was detected. Retrying once with --refresh-dependencies...'
            Write-Host "Retry log: $RetryLog"
            $GradleExitCode = Invoke-LoggedGradle -GradleArguments @('--stacktrace', '--build-cache', '--refresh-dependencies', ':core-detect:test', 'testDebugUnitTest', 'assembleDebug') -LogPath $RetryLog
            if ($GradleExitCode -ne 0) {
                throw "Gradle retry also failed (exit code $GradleExitCode). Logs: $BuildLog and $RetryLog"
            }
        } else {
            throw "Gradle failed with exit code $GradleExitCode. The detailed build log is: $BuildLog"
        }
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
    Write-Host "Opening APK folder: $Dist"
    try {
        # Open the output directory only after the APK has been built and copied successfully.
        Start-Process -FilePath (Join-Path $env:WINDIR 'explorer.exe') -ArgumentList ('"{0}"' -f $Dist) -ErrorAction Stop | Out-Null
    } catch {
        Write-Warning "The APK was built, but File Explorer could not be opened automatically: $($_.Exception.Message)"
    }
    exit 0
} catch {
    Write-Host ''
    Write-Host 'BUILD FAILED'
    Write-Host "Error: $($_.Exception.Message)"
    Write-Host 'No source files were changed by the build script. Check network access, disk space, and the first detailed error above.'
    exit 1
}
