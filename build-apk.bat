@echo off
setlocal EnableExtensions
call "%~dp0windows-build\build-apk.bat"
exit /b %ERRORLEVEL%
