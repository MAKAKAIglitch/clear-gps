@echo off
setlocal EnableExtensions
rem Keep Java selection and Gradle execution in PowerShell to preserve Unicode paths.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows-build\gradle-runner.ps1" %*
exit /b %ERRORLEVEL%
