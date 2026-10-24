@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
if not defined JAVA_HOME (
    if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%CD%\windows-build\check-environment.ps1"
if errorlevel 1 goto :fail
if not defined ANDROID_SDK_ROOT (
    if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
    if not defined ANDROID_SDK_ROOT if exist "%LOCALAPPDATA%\Android\Sdk\platforms\android-36\android.jar" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
)
if not defined ANDROID_HOME set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
call "%CD%\gradlew.bat" --no-daemon --stacktrace testDebugUnitTest assembleDebug
if errorlevel 1 goto :fail
if not exist "%CD%\dist" mkdir "%CD%\dist"
copy /Y "%CD%\app\build\outputs\apk\debug\app-debug.apk" "%CD%\dist\ClearGPS-debug.apk" >nul
if errorlevel 1 goto :fail
echo BUILD SUCCEEDED: %CD%\dist\ClearGPS-debug.apk
pause
exit /b 0
:fail
echo BUILD FAILED. Check the first error above.
pause
exit /b 1
