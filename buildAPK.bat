cls
@echo off
setlocal EnableExtensions

pushd "%~dp0"
set "ROOT=%CD%"
set "APP_ROOT=%ROOT%"

set "GRADLE_FILE=%APP_ROOT%\app\build.gradle.kts"
set "GRADLEW=%APP_ROOT%\gradlew.bat"
set "GRADLE_TASK=:app:assembleDebug"
set "PS_SCRIPT=%TEMP%\tandem_build_version.ps1"
set "VERSION_FILE=%TEMP%\tandem_build_version.txt"
set "APK_SRC=%APP_ROOT%\app\build\outputs\apk\debug\TandemSourceRT.apk"

if not exist "%GRADLE_FILE%" (
    echo ERRORE: build.gradle.kts non trovato:
    echo %GRADLE_FILE%
    exit /b 1
)

if not exist "%GRADLEW%" (
    echo ERRORE: gradlew.bat non trovato:
    echo %GRADLEW%
    exit /b 1
)

> "%PS_SCRIPT%" echo $p = '%GRADLE_FILE%'
>> "%PS_SCRIPT%" echo $txt = Get-Content -Raw -Path $p
>> "%PS_SCRIPT%" echo $m = [regex]::Match($txt, 'versionCode\s*=\s*(\d+)')
>> "%PS_SCRIPT%" echo if (-not $m.Success) { throw 'versionCode non trovato in build.gradle.kts' }
>> "%PS_SCRIPT%" echo $old = [int]$m.Groups[1].Value
>> "%PS_SCRIPT%" echo $new = $old + 1
>> "%PS_SCRIPT%" echo $txt = [regex]::Replace($txt, 'versionCode\s*=\s*\d+', 'versionCode = ' + $new, 1)
>> "%PS_SCRIPT%" echo Set-Content -Path $p -Value $txt -Encoding UTF8
>> "%PS_SCRIPT%" echo $vn = [regex]::Match($txt, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
>> "%PS_SCRIPT%" echo $version = 'v' + $vn + '.' + $new.ToString('000')
>> "%PS_SCRIPT%" echo Write-Host $version
>> "%PS_SCRIPT%" echo Set-Content -Path '%VERSION_FILE%' -Value $version -Encoding ASCII

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"
if errorlevel 1 exit /b 1

set /p VERSION=<"%VERSION_FILE%"
set "APK_RELEASE=%APP_ROOT%\app\build\outputs\apk\debug\TandemSourceRT-%VERSION%.apk"

pushd "%APP_ROOT%"
call "%GRADLEW%" %GRADLE_TASK%
set "GRADLE_EXIT=%ERRORLEVEL%"
popd
if not "%GRADLE_EXIT%"=="0" exit /b %GRADLE_EXIT%

if exist "%APK_SRC%" (
    copy /Y "%APK_SRC%" "%APK_RELEASE%" > nul
    echo APK release asset:
    echo %APK_RELEASE%
) else (
    echo ERRORE: APK non trovato:
    echo %APK_SRC%
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL
echo.

echo Adding modified tracked files...
for /f "delims=" %%f in ('git diff --name-only') do (
    echo   %%f
    git add "%%f"
)

echo.
echo Commit: %VERSION%
git commit -m "%VERSION%"

echo.
echo Next release commands:
echo git tag %VERSION%
echo git push origin main
echo git push origin %VERSION%
echo.
echo Upload this file to GitHub Release %VERSION%:
echo %APK_RELEASE%

popd

endlocal
