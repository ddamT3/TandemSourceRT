@echo off
setlocal EnableExtensions
pushd "%~dp0"
if errorlevel 1 exit /b 1

if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat non trovato
    popd
    exit /b 1
)

if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)

call gradlew.bat :app:assembleDebug --offline
if errorlevel 1 (
    echo BUILD FAILED
    popd
    exit /b 1
)

set "APK=app\build\outputs\apk\debug\TandemSourceRT.apk"
if not exist "%APK%" (
    echo ERROR: APK non trovato
    popd
    exit /b 1
)

for /f "tokens=4" %%V in ('findstr /C:"val release_versionName" "app\build.gradle.kts"') do set "VERSION=%%~V"
if not defined VERSION (echo ERROR: versione non trovata & popd & exit /b 1)
set "RELEASE=app\build\outputs\apk\debug\TandemSourceRT-v%VERSION%.apk"
copy /Y "%APK%" "%RELEASE%" >nul
if errorlevel 1 (echo ERROR: copia APK fallita & popd & exit /b 1)
echo APK GitHub Release: %RELEASE%
echo BUILD SUCCESSFUL
echo Versione invariata. Nessun commit, push o deploy.
echo APK: %APK%
popd
endlocal
exit /b 0