# TandemSourceRT --- Toolchain e ambiente

**Aggiornato:** 2026-08-17\
**Repository verificato:** `E:\plinco\Documenti\Github\TandemSourceRT`

## Regole di progetto

-   Per ogni modifica indicare sempre la **path del file da
    modificare**.
-   Per le diff usare **sempre `gitDiff.bat`**.
-   Gli script PowerShell devono terminare con **invio/riga vuota
    finale**.
-   Non committare versioni di prova solo per testarle.
-   Il runtime dell'app è interamente Kotlin. Il file Python sotto
    `app\src\test\python` è soltanto un riferimento per i test di parità.

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
| Kotlin serialization JSON | 1.6.3 | `app/build.gradle.kts` |
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
-   `app\src\main\java\com\example\tandemapp\data\TandemAuthProvider.kt`

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

Build completamente pulita:

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

Log OAuth Kotlin:

``` powershell
adb logcat -c
adb logcat -d | Select-String "TandemAuth"
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
GET /api/reports/bff/pumper/{pumperId}
```

`pump-logs` usa parametri quali `pumperId`, `startDate`, `endDate`,
`eventIds` e restituisce JSON con almeno `events` e `clockChanges`.

Il nuovo flusso ricava gli `assignmentId` da `devices[]`; non usa più
`pumpeventmetadata` per ottenere i `tconnectDeviceId`. Il formato JSON
BFF richiede un adattatore Kotlin verso il dataset interno `cgm`, `bolus`,
`basal`, `iob`, `cho` e `deviceState`.

Dalla serie `v02.01.xxx`, autenticazione OAuth/PKCE, download, export e
adattamento dei JSON BFF sono implementati interamente in Kotlin. Non sono
più inclusi Chaquopy, un interprete Python o la libreria `requests` nell'APK.

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
