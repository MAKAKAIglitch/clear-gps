$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$GradleArguments = @($args)

function Invoke-PathHelper {
    param([string]$ScriptPath)

    $ResultFile = Join-Path $env:TEMP ("cleargps-gradle-setup-" + [guid]::NewGuid().ToString('N') + '.txt')
    try {
        & (Join-Path $PSHOME 'powershell.exe') -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath -ResultFile $ResultFile | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Setup helper failed: $([IO.Path]::GetFileName($ScriptPath)) (exit code $LASTEXITCODE)."
        }
        if (-not (Test-Path -LiteralPath $ResultFile)) {
            throw "Setup helper did not return a path: $([IO.Path]::GetFileName($ScriptPath))."
        }
        return [IO.File]::ReadAllText($ResultFile, [System.Text.Encoding]::UTF8).Trim()
    } finally {
        if (Test-Path -LiteralPath $ResultFile) {
            Remove-Item -LiteralPath $ResultFile -Force -ErrorAction SilentlyContinue
        }
    }
}

try {
    $JavaResolver = Join-Path $PSScriptRoot 'ensure-java.ps1'
    $env:JAVA_HOME = Invoke-PathHelper -ScriptPath $JavaResolver
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        throw "JDK resolver returned an invalid path: $env:JAVA_HOME"
    }
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

    $GradleBootstrap = Join-Path $PSScriptRoot 'ensure-gradle.ps1'
    & (Join-Path $PSHOME 'powershell.exe') -NoLogo -NoProfile -ExecutionPolicy Bypass -File $GradleBootstrap | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle bootstrap failed with exit code $LASTEXITCODE."
    }

    $GradleExe = Join-Path $env:USERPROFILE '.gradle\clear-gps\gradle-8.14.6\bin\gradle.bat'
    if (-not (Test-Path -LiteralPath $GradleExe)) {
        throw "Gradle 8.14.6 is still unavailable at $GradleExe."
    }
    Write-Host "Running Gradle 8.14.6 with JDK from $env:JAVA_HOME"
    & $GradleExe @GradleArguments
    $GradleExitCode = $LASTEXITCODE
    if ($null -eq $GradleExitCode) { $GradleExitCode = 0 }
    exit [int]$GradleExitCode
} catch {
    Write-Host "Gradle launcher failed: $($_.Exception.Message)"
    exit 1
}
