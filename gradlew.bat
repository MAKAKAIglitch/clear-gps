@echo off
setlocal EnableExtensions
set "PROJECT_ROOT=%~dp0"
set "GRADLE_HOME=%USERPROFILE%\.gradle\clear-gps\gradle-8.14.6"
set "JAVA_RESULT=%TEMP%\cleargps-java-%RANDOM%-%RANDOM%.txt"

rem Resolve Java even when JAVA_HOME already points to an unsupported old JDK.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%windows-build\ensure-java.ps1" -ResultFile "%JAVA_RESULT%"
if errorlevel 1 (
    del "%JAVA_RESULT%" >nul 2>nul
    exit /b 1
)
if not exist "%JAVA_RESULT%" (
    echo ERROR: Java resolver did not return a JAVA_HOME path.
    exit /b 1
)
set "JAVA_HOME="
set /p "JAVA_HOME="<"%JAVA_RESULT%"
del "%JAVA_RESULT%" >nul 2>nul
if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME could not be read from the resolver output.
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%windows-build\ensure-gradle.ps1"
if errorlevel 1 exit /b 1
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo ERROR: Gradle 8.14.6 is unavailable after bootstrap.
    exit /b 1
)
call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
