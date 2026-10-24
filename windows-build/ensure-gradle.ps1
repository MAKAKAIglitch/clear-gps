$ErrorActionPreference = 'Stop'
$GradleVersion = '8.14.6'
$ExpectedSha256 = '7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24'
$InstallRoot = Join-Path $env:USERPROFILE '.gradle\clear-gps'
$InstallDir = Join-Path $InstallRoot "gradle-$GradleVersion"
if (Test-Path (Join-Path $InstallDir 'bin\gradle.bat')) { exit 0 }
New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
$ZipPath = Join-Path $env:TEMP "gradle-$GradleVersion-bin.zip"
$ExtractRoot = Join-Path $env:TEMP "clear-gps-gradle-$GradleVersion"
$Uri = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
try {
    Write-Host "Downloading official Gradle $GradleVersion..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $Uri -OutFile $ZipPath -UseBasicParsing
    $ActualSha256 = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ActualSha256 -ne $ExpectedSha256) { throw "SHA-256 mismatch: expected $ExpectedSha256; got $ActualSha256. Archive rejected." }
    if (Test-Path $ExtractRoot) { Remove-Item -LiteralPath $ExtractRoot -Recurse -Force }
    New-Item -ItemType Directory -Path $ExtractRoot | Out-Null
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractRoot -Force
    $Extracted = Join-Path $ExtractRoot "gradle-$GradleVersion"
    if (-not (Test-Path (Join-Path $Extracted 'bin\gradle.bat'))) { throw 'Expected Gradle executable not found in archive.' }
    if (Test-Path $InstallDir) { Remove-Item -LiteralPath $InstallDir -Recurse -Force }
    Move-Item -LiteralPath $Extracted -Destination $InstallDir
    Write-Host "Gradle installed under $InstallDir"
} catch {
    Write-Error "Gradle setup failed: $($_.Exception.Message)"
    exit 1
} finally {
    if (Test-Path $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force -ErrorAction SilentlyContinue }
    if (Test-Path $ExtractRoot) { Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
