@echo off
setlocal EnableExtensions
set "PROJECT_ROOT=%~dp0"
set "GRADLE_HOME=%USERPROFILE%\.gradle\clear-gps\gradle-8.14.6"
if not defined JAVA_HOME (
    if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%windows-build\ensure-gradle.ps1"
if errorlevel 1 exit /b 1
if not exist "%GRADLE_HOME%\bin\gradle.bat" exit /b 1
call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
