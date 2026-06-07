cls
@echo off
setlocal EnableExtensions

pushd "%~dp0"
set "ROOT=%CD%"
set "APP_ROOT=%ROOT%"

set "NOINCR=0"
if /I "%~1"=="-noincr" set "NOINCR=1"

set "GRADLE_FILE=%APP_ROOT%\app\build.gradle.kts"
set "GRADLEW=%APP_ROOT%\gradlew.bat"
set "GRADLE_TASK=:app:assembleDebug"
set "PS_SCRIPT=%TEMP%\tandem_build_version.ps1"
set "VERSION_FILE=%TEMP%\tandem_build_version.txt"
set "APK_SRC=%APP_ROOT%\app\build\outputs\apk\debug\TandemSourceRT.apk"
set "LOCAL_PROPERTIES=%APP_ROOT%\local.properties"

if not exist "%GRADLE_FILE%" (
    echo ERRORE: build.gradle.kts non trovato:
    echo %GRADLE_FILE%
    popd
    exit /b 1
)

if not exist "%GRADLEW%" (
    echo ERRORE: gradlew.bat non trovato:
    echo %GRADLEW%
    popd
    exit /b 1
)

if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)

if not exist "%LOCAL_PROPERTIES%" (
    echo local.properties non trovato.

    if defined ANDROID_HOME (
        echo Creo local.properties usando ANDROID_HOME:
        echo %ANDROID_HOME%
        > "%LOCAL_PROPERTIES%" echo sdk.dir=%ANDROID_HOME%
    ) else (
        echo ERRORE: local.properties non trovato e ANDROID_HOME non definito.
        echo.
        echo Crea manualmente local.properties nella root del progetto, esempio:
        echo sdk.dir=C\:\\Users\\USERNAME\\AppData\\Local\\Android\\Sdk
        popd
        exit /b 1
    )
)

if "%NOINCR%"=="1" (
    echo.
    echo NOINCR mode: versionCode will NOT be modified.
    echo No Git commit will be created.
    echo.
)

if "%NOINCR%"=="1" goto WRITE_VERSION_NOINCR
goto WRITE_VERSION_INCR

:WRITE_VERSION_NOINCR
> "%PS_SCRIPT%" echo $p = '%GRADLE_FILE%'
>> "%PS_SCRIPT%" echo $txt = Get-Content -Raw -Path $p
>> "%PS_SCRIPT%" echo $mCode = [regex]::Match($txt, 'versionCode\s*=\s*(\d+)')
>> "%PS_SCRIPT%" echo if (-not $mCode.Success) { throw 'versionCode non trovato in build.gradle.kts' }
>> "%PS_SCRIPT%" echo $mName = [regex]::Match($txt, 'versionName\s*=\s*"([^"]+)"')
>> "%PS_SCRIPT%" echo if (-not $mName.Success) { throw 'versionName non trovato in build.gradle.kts' }
>> "%PS_SCRIPT%" echo $vc = [int]$mCode.Groups[1].Value
>> "%PS_SCRIPT%" echo $vn = $mName.Groups[1].Value
>> "%PS_SCRIPT%" echo $version = 'v' + $vn + '.' + $vc.ToString('000')
>> "%PS_SCRIPT%" echo Write-Host $version
>> "%PS_SCRIPT%" echo Set-Content -Path '%VERSION_FILE%' -Value $version -Encoding ASCII
goto RUN_PS_SCRIPT

:WRITE_VERSION_INCR
> "%PS_SCRIPT%" echo $p = '%GRADLE_FILE%'
>> "%PS_SCRIPT%" echo $txt = Get-Content -Raw -Path $p
>> "%PS_SCRIPT%" echo $mCode = [regex]::Match($txt, 'versionCode\s*=\s*(\d+)')
>> "%PS_SCRIPT%" echo if (-not $mCode.Success) { throw 'versionCode non trovato in build.gradle.kts' }
>> "%PS_SCRIPT%" echo $old = [int]$mCode.Groups[1].Value
>> "%PS_SCRIPT%" echo $new = $old + 1
>> "%PS_SCRIPT%" echo $txt = [regex]::Replace($txt, 'versionCode\s*=\s*\d+', 'versionCode = ' + $new, 1)
>> "%PS_SCRIPT%" echo Set-Content -Path $p -Value $txt -Encoding UTF8
>> "%PS_SCRIPT%" echo $mName = [regex]::Match($txt, 'versionName\s*=\s*"([^"]+)"')
>> "%PS_SCRIPT%" echo if (-not $mName.Success) { throw 'versionName non trovato in build.gradle.kts' }
>> "%PS_SCRIPT%" echo $vn = $mName.Groups[1].Value
>> "%PS_SCRIPT%" echo $version = 'v' + $vn + '.' + $new.ToString('000')
>> "%PS_SCRIPT%" echo Write-Host $version
>> "%PS_SCRIPT%" echo Set-Content -Path '%VERSION_FILE%' -Value $version -Encoding ASCII
goto RUN_PS_SCRIPT

:RUN_PS_SCRIPT
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"
if errorlevel 1 (
    popd
    exit /b 1
)

set /p VERSION=<"%VERSION_FILE%"
set "APK_RELEASE=%APP_ROOT%\app\build\outputs\apk\debug\TandemSourceRT-%VERSION%.apk"

pushd "%APP_ROOT%"
call "%GRADLEW%" %GRADLE_TASK%
set "GRADLE_EXIT=%ERRORLEVEL%"
popd
if not "%GRADLE_EXIT%"=="0" (
    popd
    exit /b %GRADLE_EXIT%
)

if exist "%APK_SRC%" (
    copy /Y "%APK_SRC%" "%APK_RELEASE%" > nul
    echo APK release asset:
    echo %APK_RELEASE%
) else (
    echo ERRORE: APK non trovato:
    echo %APK_SRC%
    popd
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL
echo.

if "%NOINCR%"=="1" (
    echo NOINCR mode completed.
    echo Version not incremented.
    echo No Git commit created.
    echo.
    echo APK generated:
    echo %APK_RELEASE%
    popd
    endlocal
    exit /b 0
)

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
