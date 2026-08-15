# TandemSourceRT --- Toolchain e ambiente

**Aggiornato:** 2026-08-15\
**Repository verificato:** `E:\plinco\Documenti\Github\TandemSourceRT`

## Regole di progetto

-   Per ogni modifica indicare sempre la **path del file da
    modificare**.
-   Per le diff usare **sempre `gitDiff.bat`**.
-   Gli script PowerShell devono terminare con **invio/riga vuota
    finale**.
-   Non committare versioni di prova solo per testarle.
-   Il sorgente Python è `app\src\main\python\tandem_embedded.py`; non
    modificare come sorgente la copia sotto `app\build`.

## Tool e versioni

| Componente | Versione verificata | Fonte |
|---|---:|---|
| Windows | 10.0.19045 | sistema locale |
| PowerShell | 7.6.4 | `$PSVersionTable` |
| Git for Windows | 2.55.0.windows.4 | `git --version` |
| JDK | Eclipse Temurin 17.0.20+8 | `java -version` |
| Gradle Wrapper | 8.7 | `gradle-wrapper.properties` |
| Android Gradle Plugin | 8.6.1 | `build.gradle.kts` |
| Kotlin Android/Serialization | 1.9.24 | file Gradle |
| Compose Compiler | 1.5.14 | `app/build.gradle.kts` |
| Compose BOM | 2024.06.00 | `app/build.gradle.kts` |
| Chaquopy | 17.0.0 | file Gradle |
| Python embedded | 3.11 | configurazione Chaquopy e log Android |
| Python `requests` | 2.32.3 | configurazione Chaquopy |
| Android Platform Tools | 37.0.1 | SDK `source.properties` |
| `compileSdk` / `targetSdk` | 35 / 35 | `app/build.gradle.kts` |
| `minSdk` | 29 | `app/build.gradle.kts` |

Altri strumenti usati: Android Studio, Android SDK, `adb`, Gradle
Wrapper, browser DevTools/F12 con Network/HAR e GitHub sul branch
`main`.

Host Tandem osservato: `https://source.eu.tandemdiabetes.com`.
Package Android: `com.example.tandemapp.st`.
Activity: `com.example.tandemapp.st.MainActivity`.

## Versioni applicazione

-   Baseline Git precedente: **v01.01.011**, commit `4881db8`.
-   Dalla serie **v01.02.xxx** l'app usa i nuovi endpoint Tandem Source
    descritti nella sezione API.
-   La revisione `xxx` è gestita da `buildAPK.bat` e va da `000` a
    `999`.
-   Il `versionCode` Android è calcolato come
    `major * 100000 + minor * 1000 + revisione`, così resta positivo e
    crescente senza coincidere con la sola revisione visualizzata.

## File di configurazione da preservare/controllare

-   `gradle\wrapper\gradle-wrapper.properties`
-   `settings.gradle.kts` o `settings.gradle`
-   `build.gradle.kts` o `build.gradle`
-   `app\build.gradle.kts`
-   `gradle\libs.versions.toml` se presente
-   `local.properties` --- locale al PC, contiene `sdk.dir`
-   `gitDiff.bat`
-   `app\src\main\python\tandem_embedded.py`

`local.properties` aveva causato un build failure perché conteneva
`sdk.dir=` vuoto. Sul nuovo PC deve puntare all'SDK realmente
installato, per esempio:

``` properties
sdk.dir=C:\\Users\\<utente>\\AppData\\Local\\Android\\Sdk
```

## Build e installazione

Build debug:

``` powershell
.\gradlew.bat clean assembleDebug
```

Build completamente pulita, utile con Chaquopy:

``` powershell
Remove-Item -Recurse -Force .\.gradle, .\build, .\app\build -ErrorAction SilentlyContinue
.\gradlew.bat clean assembleDebug --no-build-cache --rerun-tasks
```

ADB:

``` powershell
adb kill-server
adb start-server
adb devices
```

Installazione della build prodotta:

``` powershell
adb install -r ".\app\build\outputs\apk\debug\TandemSourceRT-v01.02.000.apk"
```

Reinstallazione realmente pulita:

``` powershell
adb uninstall com.example.tandemapp.st
adb install ".\app\build\outputs\apk\debug\TandemSourceRT-v01.02.000.apk"
```

Log Python:

``` powershell
adb logcat -c
adb logcat -d | Select-String "PYTHON"
```

## API Tandem rilevanti e compatibilità

Fino alla serie `v01.01.xxx` era usato il flusso legacy:

``` text
/api/reports/reportsfacade/{pumperId}/pumpeventmetadata
/api/reports/reportsfacade/pumpevents/{pumperId}/{tconnectDeviceId}
```

Dalla serie `v01.02.xxx` viene usato il nuovo flusso Tandem Source:

``` text
GET /api/pumpers/pumpers/{pumperId}
GET /api/reports/bff/pump-logs/{assignmentId}
```

`pump-logs` usa parametri quali `pumperId`, `startDate`, `endDate`,
`eventIds` e restituisce JSON con almeno `events` e `clockChanges`.

Il nuovo flusso ricava gli `assignmentId` da `devices[]`; non usa più
`pumpeventmetadata` per ottenere i `tconnectDeviceId`. Il formato JSON
BFF richiede un adattatore verso il dataset interno `cgm`, `bolus`,
`basal`, `iob`, `cho` e `deviceState`.

## Inventario da eseguire sul vecchio PC

``` powershell
cd E:\plinco\Documenti\Github\TandemSourceRT

git --version
git status
git log --oneline -5
git remote -v

java -version
adb version
.\gradlew.bat --version

Get-Content .\gradle\wrapper\gradle-wrapper.properties
Get-Content .\app\build.gradle.kts

.\gitDiff.bat
```

Salvare questo output: completa in modo certo le versioni non
disponibili nel checkpoint.

## Setup nuovo PC

Installare Git, un JDK compatibile con l'Android Gradle Plugin del
repository, Android Studio oppure Android SDK/command-line tools e
Android Platform Tools. Clonare il repository, impostare
`local.properties`, usare il `gradlew.bat` incluso nel progetto e
verificare il telefono con `adb devices`.

Non mettere nel repository password, bearer token, ID token, cookie, HAR
non sanitizzati o dati personali.
