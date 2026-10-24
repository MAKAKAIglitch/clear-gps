@echo off
setlocal EnableExtensions
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1"
set "BUILD_EXIT=%ERRORLEVEL%"
if not "%CLEAR_GPS_NO_PAUSE%"=="1" pause
exit /b %BUILD_EXIT%
