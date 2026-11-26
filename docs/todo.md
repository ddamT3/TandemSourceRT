# TODO — TandemSourceRT

> Registro delle attività note. Gli stati descrivono evidenze disponibili, non attestano test o release non eseguiti.

## Convenzioni
- `PLANNED`: previsto; `IN_PROGRESS`: in lavorazione; `BLOCKED`: impedimento; `DONE`: implementato; `VERIFIED`: test pertinente superato.
- Aggiornare per incremento e distinguere implementazione, test, commit, push e installazione.
- Preservare tutti i PASS già raggiunti, senza inventarne di nuovi.

## Attività aperte

### TS-001 — OutOfMemoryError durante download eventi Tandem
- **Stato:** `IN_PROGRESS`
- **Evidenza:** HONOR 9X, `com.example.tandemapp.st.dev`, versione `26.11.002`; OOM con heap limitato a circa 384 MiB durante `pump_logs`.
- **Diagnosi aggiornata:** il download operativo usa `downloadEventsStream()` e `PumpEventsAdapter.decodeStream()`. Il percorso `getJson()/readText()` rimane rilevante per altre operazioni, ma non descrive il download operativo attuale.
- **Intervento:** analizzare dimensioni, copie, buffering e chiamanti; introdurre gestione a memoria limitata senza perdita di eventi o metadati; verificare retention e cache.
- **Verifiche:** test mirati sintetici, build debug offline quando applicabile, riproduzione sul dispositivo; nessuna cancellazione dati, reinstallazione o log persistente non autorizzati.
- **Accettazione:** assenza OOM sul caso riproducibile, completezza dati, UI responsiva, nessuna regressione.

#### Evidenze tecniche disponibili
- Prima patch locale su `PumpEventsAdapter.kt`: eliminazione della lista completa dei DTO e decodifica su richiesta. Test unitari selezionati e build debug offline: PASS.
- Benchmark sintetico da 2.000 eventi: parita' tra decoder PASS.
- Benchmark sintetico da 20.000 eventi: PASS, 228 ms, heap osservato da 82 a 182 MiB, limite JVM 512 MiB.
- Benchmark sintetico da 100.000 eventi con heap JVM limitato a 384 MiB: PASS, 1.946 ms, picco campionato 377 MiB.
- Benchmark strumentato da 100.000 eventi: PASS; heap prima del parsing 175 MiB, dopo parsing JSON 360 MiB, dopo costruzione dataset 374 MiB; picco campionato 383 MiB; parsing 347 ms, decodifica 1.573 ms.
- I valori heap sono misure di una JVM desktop con dati sintetici, non misure Android e non equivalgono necessariamente alla memoria allocata da ciascuna fase.
- La strumentazione sperimentale dei benchmark e' temporanea. Il Memory Profiler permanente approvato il 2026-11-24 e' distinto, disabilitato per default e soggetto ai test di overhead e privacy.
- Nessuna verifica conclusiva dell'OOM sul dispositivo HONOR 9X. Nessuna installazione o release autorizzata da queste evidenze.

#### Ipotesi di soluzione da validare
- Mantenere JSON come formato originale Tandem; non cambiare il contratto del server.
- Valutare la scrittura progressiva del payload JSON originale in un archivio locale privato, senza costruire stabilmente l'intero albero `JsonObject/JsonArray` in RAM.
- Valutare un parser streaming che estragga i dati necessari alla visualizzazione e conservi riferimenti verificabili agli eventi originali.
- Definire un identificatore persistente del payload e un accesso verificabile agli eventi tramite `payloadIndex` ed `eventIndex`, senza perdere ordine originale, campi sconosciuti e metadati.
- Conservare `clockChanges`, `sequenceNumber`, `sequenceGroup`, `pumpDateTime`, `estimatedDateTime`, `egvTimeStamp` e `deviceAssignmentId`.
- Progettare compatibilita' di lettura delle cache esistenti, migrazione non distruttiva o strategia esplicita di compatibilita', senza cancellare dati.
- Valutare scrittura atomica, file incompleti, rollback, spazio disco, retention tramite `AppConfig.HISTORY_DAYS`, export e pulizia degli archivi non piu' referenziati.
- Non adottare SQLite o altri formati senza una verifica comparativa motivata.

#### Stime e incertezze
- Il target di picco inferiore a 250 MiB su 100.000 eventi sintetici con heap 384 MiB e' un obiettivo preliminare da verificare, NON una previsione garantita ne' un criterio sufficiente di risoluzione.
- L'eventuale riduzione di RAM, il costo in spazio disco, la latenza di lettura, il tempo di download e l'impatto sulla UI sono sconosciuti finche' non misurati.
- La dimensione e la distribuzione dei payload reali potrebbero differire sostanzialmente dai dati sintetici.
- La fattibilita' della conservazione su file e della lettura delle cache precedenti e' da dimostrare con un prototipo e test di compatibilita'.

### TS-001 — Memory Profiler permanente (approvato 2026-11-24)

- Stato: `IN_PROGRESS`. Prioritario rispetto all'integrazione A/B.
- Estendere `StartupMemoryProfiler`: esistono gia' campioni puntuali Logcat di heap Java used/committed/max, native allocated e PSS.
- Misurare START/END, durata monotona, delta heap, picco campionato e percentuale del limite; dimensioni payload e conteggi aggregati, senza contenuti sanitari.
- Fasi: avvio, lettura e decode cache, preparazione dashboard, autenticazione, download pump_logs, parsing, estrazione, merge, salvataggio, aggiornamento UI e idle.
- Switch persistente default OFF; campioni heap ogni 250 ms durante aggiornamento, PSS ogni 2 s, idle ogni 5 s. Nessun GC forzato, sleep artificiale o riferimento ai payload.
- CSV privato, retention 48 ore e limite complessivo 5 MiB, scrittura progressiva e recupero dei record completi dopo crash; export manuale e reset esplicito delle sole statistiche. Nessun dato sanitario, credenziale, identificativo assignment o corpo HTTP.
- UI Login/Settings: switch, heap corrente/massimo, picco campionato, percentuale, storico limitato, riepilogo per fase, export CSV e reset statistiche.
- Test: OFF senza campionamento o scritture diagnostiche, overhead RAM/CPU/I/O, concorrenza, rotazione, crash, privacy, correttezza unita' e timestamp, errori I/O non bloccanti. Il picco campionato e' un limite inferiore del picco effettivo.
- Dispositivo baseline: HONOR 9X STK_LX1, Android 10, heapgrowthlimit 384m, heapsize 512m, package com.example.tandemapp.st.dev, versione 26.11.002 (versionCode 2611002). ADB autorizzato; processo non trovato durante l'ultima misura, baseline idle NON ESEGUITA.

| Gate | Stato | Attivita' |
|---|---|---|
| R1-P1 | `VERIFIED` (analisi statica) | Verifica profiler esistente |
| R1-P2 | `PLANNED` | Estensione misure e campionamento |
| R1-P3 | `PLANNED` | CSV persistente limitato, export e recupero |
| R1-P4 | `PLANNED` | UI diagnostica |
| R1-P5 | `PLANNED` | Test sintetici, build offline e baseline dispositivo previa autorizzazione installazione |

R2 rimane in corso come prototipo isolato; integrazione R4 subordinata ai gate di compatibilita' e baseline. R5 confronta misure omogenee prima/dopo; R6 verifica sul dispositivo reale. Nessun test applicativo nuovo eseguito.

### TS-002 — Navigazione inattesa dal Login
- **Stato:** `PLANNED`
- **Sintomo da verificare:** l'opzione «Check for updates automatically» potrebbe provocare il passaggio alla Dashboard.
- **Intervento:** riprodurre; analizzare eventi UI e auto-login; correggere solo se confermato; test mirati.
- **Accettazione:** il checkbox modifica la preferenza senza navigazioni impreviste.

### TS-003 — Consolidamento regole e documentazione
- **Stato:** `IN_PROGRESS`
- **Intervento:** organizzare `docs/rules.md` in sezione specifica e sezione comune; mantenere `docs/todo.md` aggiornato.
- **Verifica:** confrontare con la copia locale prima di sincronizzare per non perdere modifiche non pubblicate.

## Roadmap TS-001

> Roadmap incrementale e revisionabile. Le stime sono ipotesi da verificare; le fasi non implicano approvazione di implementazione, installazione o rilascio.

| Fase | Stato | Attivita' | Evidenza richiesta |
|---|---|---|---|
| R0 - Diagnosi | `VERIFIED` limitatamente ai benchmark | Individuare le fasi di maggiore consumo RAM | Test sintetici, misure per fase e limiti documentati |
| R1 - Contratto dati | `PLANNED` | Mappare uso di `pumpEventsSources`, cache, timeline, export e retention | Matrice di dipendenze, invarianti e casi limite |
| R2 - Prototipo | `PLANNED` | Verificare archivio JSON locale e parser streaming a memoria limitata | Prototipo sintetico con confronto RAM, CPU, disco e integrita' |
| R3 - Compatibilita' | `PLANNED` | Definire lettura delle cache precedenti e gestione file atomica | Test di cache vecchie/nuove, errori I/O e recupero |
| R4 - Integrazione | `PLANNED` | Integrare in piccoli incrementi dopo esito positivo del prototipo | Test mirati, parita' dati, build debug offline |
| R5 - Prestazioni | `PLANNED` | Ripetere benchmark identici e verificare margine RAM | Confronto prima/dopo su 20.000 e 100.000 eventi |
| R6 - Dispositivo | `PLANNED` | Verifica controllata su HONOR 9X previa autorizzazione | Nessun OOM riproducibile, dati completi, UI responsiva |
| R7 - Chiusura | `PLANNED` | Valutare accettazione TS-001 e documentare limiti residui | Evidenze tecniche e approvazione esplicita |

### Rischi da controllare con i test
- **Integrita' e provenienza:** corrispondenza esatta tra payload archiviato, `payloadIndex`, `eventIndex` e CGM; nessuna perdita o riordinamento degli originali.
- **Cache e migrazione:** nessuna perdita di dati esistenti, riferimenti invalidi, cache incompatibili lette senza controllo o regressioni della timeline.
- **Clock e metadati:** conservazione integrale di `clockChanges`, campi sconosciuti e metadati temporali.
- **I/O e recupero:** download interrotti, file parziali, errori disco, scritture atomiche e pulizia coerente con retention.
- **Prestazioni:** RAM, GC, CPU, latenza, spazio disco e responsivita' UI durante download, merge, storico ed export.
- **Privacy:** file locali privati dell'app, nessuna esposizione di payload sanitari nei log o nei test.
- **Differenze di ambiente:** JVM desktop e Android non sono equivalenti; il PASS sintetico non chiude l'OOM reale.

### Regole di avanzamento
- Ogni fase registra separatamente fatto, da fare, test eseguiti e rischi residui.
- Nessuna implementazione architetturale definitiva prima della verifica del prototipo e della compatibilita'.
- Aggiornare la roadmap se misure o test smentiscono le ipotesi.
- Nessun commit, push, bump versione, installazione APK o cancellazione dati senza autorizzazione esplicita.
## Rischi trasversali
- Integrità e provenienza dei dati CGM/Tandem e riferimenti `payloadIndex`.
- OOM, cache obsolete, duplicazioni e blocchi UI.
- Privacy sanitaria e segreti nei log.
- Regressioni Android e differenze fra dispositivi.
- File temporanei, backup e diagnostica persistente non necessari.
- Divergenze tra working tree locale e GitHub.

## Cronologia verifiche
- 2026-11-22: identificato crash OOM tramite log ADB; **correzione NON ESEGUITA**.
- 2026-11-22: preparata riorganizzazione documentale; i test applicativi non sono pertinenti a questa modifica.
- 2026-11-23: TS-001 in corso; test sintetici e benchmark 384 MiB superati, ma picco campionato 383 MiB. Definita ipotesi di archivio JSON locale con parsing streaming, ancora da validare; OOM reale non risolto.

## TS-001 — Decisione architetturale: snapshot A/B e RAM di 3 giorni

- **Stato:** `PLANNED` (architettura concordata, non implementata).
- **RAM:** mantenere soltanto gli ultimi **3 giorni** di dati elaborati; nessun payload JSON originale completo residente in memoria. Lo storico precedente viene caricato da disco su richiesta, con cache limitata.
- **Persistenza:** due slot privati A/B, ciascuno contenente una generazione completa e coerente: JSON originali, dati elaborati, indici e metadati. La retention rimane definita da `AppConfig.HISTORY_DAYS` e non dalla finestra RAM.
- **Aggiornamento:** scaricare integralmente la finestra storica richiesta nello slot inattivo, con elaborazione streaming e memoria limitata. Nessun merge incrementale fra generazioni. Verificare tutte le richieste/assignment, copertura temporale, JSON, integrità dei riferimenti e scrittura durabile prima di pubblicare il nuovo snapshot. Un HTTP 200 non prova la completezza.
- **Attivazione:** manifesto persistente con identificativo di generazione, cambiato atomicamente soltanto dopo validazione. La UI e le nuove letture usano la nuova generazione; i lettori già attivi possono completare sulla precedente. Liberare il vecchio slot soltanto quando non è più referenziato. In caso di errore, mantenere lo snapshot precedente; al riavvio recuperare soltanto generazioni complete e validate.
- **Integrità:** preservare `eventIndex` originale, `payloadIndex` verificabile, `sequenceNumber`, `sequenceGroup`, `pumpDateTime`, `estimatedDateTime`, `egvTimeStamp`, `deviceAssignmentId`, `clockChanges`, campi JSON sconosciuti e molteplicità CGM. Distinguere originali, normalizzati e visualizzati; garantire coerenza tra CGM e sorgenti.
- **Migrazione:** non invalidare o cancellare la cache attuale da circa 66 MiB prima di aver creato e validato una generazione recuperabile. Verificare spazio disponibile e rollback; nessuna installazione, reset dati o cancellazione non autorizzata.
- **Target sperimentali:** heap Java stabile con dashboard aperta **< 40 MiB**; picco durante aggiornamento **< 120 MiB**. Sono obiettivi da misurare, non risultati acquisiti.
- **Diagnosi HONOR 9X del 2026-11-23:** cache 66 MiB; heap Java 11 MiB prima della lettura, 376 MiB dopo decodifica e dopo GC, 379/384 MiB prima di `pump_logs` (HTTP 200). Il GC osservato non ha ridotto l'heap utilizzato; non attribuire ancora un valore esatto alle singole strutture. Nessuna correzione verificata sul dispositivo.

### Roadmap TS-001 A/B

| Gate | Stato | Attività e criterio di uscita |
| --- | --- | --- |
| **R0 — Diagnosi** | `IN_PROGRESS` | Evidenze ADB raccolte; completare analisi del picco e distinguere memoria stabile/transitoria. |
| **R1 — Contratto dati** | `PLANNED` | Specificare schema snapshot, manifesto, identificativi, copertura, completezza, retention e riferimenti di provenienza. |
| **R2 — Prototipo isolato** | `PLANNED` | Streaming verso slot inattivo, verifica, switch atomico, simulazioni di crash e riavvio con dati sintetici. |
| **R3 — Migrazione sicura** | `PLANNED` | Compatibilità con cache esistente, spazio libero, conversione non distruttiva e recupero dopo errore. |
| **R4 — Integrazione** | `PLANNED` | Repository, dashboard, storico, export, RAM 3 giorni e lettori concorrenti senza payload originali in heap. |
| **R5 — Test** | `PLANNED` | Parità eventi, metadati, duplicati, clockChanges, retention, download parziale, disco pieno, interruzioni, concorrenza; test sintetici e build debug offline. |
| **R6 — Dispositivo** | `PLANNED` | Misurare heap stabile e picco sull'HONOR 9X; verificare UI e assenza OOM, senza cancellazioni/installazioni implicite. |
| **R7 — Consolidamento** | `PLANNED` | Documentare risultati, rischi residui e criteri di chiusura; `VERIFIED` solo dopo prove pertinenti. |

**Rischi da presidiare nei test:** download integrale incompleto ma apparentemente valido; indici non coerenti; perdita di metadati e campi sconosciuti; letture concorrenti durante lo switch; interruzione fra scrittura e attivazione; spazio insufficiente; copie temporanee che causano OOM; regressioni di dashboard, storico, export e retention. Non usare `System.gc()` o `Thread.sleep()` come soluzione all'OOM.

### TS-001 - Consolidamento Memory Profiler MEM-09

Data: 2026-11-24.
Branch: work/pump-events-oom-sync-20261122.
HEAD di riferimento: 245bd3c.
Modifiche locali non ancora committate.

Stato complessivo TS-001: IN_PROGRESS.

Questa sezione aggiorna lo stato operativo del Memory Profiler.
Le evidenze storiche precedenti rimangono valide nel loro contesto.

#### Checkpoint JVM

| Checkpoint | Stato | Evidenza |
|---|---|---|
| MEM-01 | VERIFIED JVM | Isolamento eccezioni diagnostiche |
| MEM-02 | VERIFIED JVM | Reset archivio, generazioni e stato reset |
| MEM-03 | VERIFIED JVM | Recupero record CSV completi |
| MEM-04 | VERIFIED JVM | Retention e manutenzione periodica |
| MEM-05 | VERIFIED JVM | Indice dimensionale incrementale |
| MEM-06.1 | VERIFIED JVM | Concorrenza archivio |
| MEM-06.2a | VERIFIED JVM | Iniezione campioni sintetici |
| MEM-06.2b | VERIFIED JVM | Profiler attivo e strutture limitate |
| MEM-06.3 | VERIFIED JVM | Concorrenza export/reset |
| MEM-06.4 | VERIFIED JVM | Correlazione profiler e CSV |
| MEM-07.1 | VERIFIED JVM | Limite archivio anche in manutenzione |
| MEM-07.2 | VERIFIED JVM | Benchmark sintetici completati |
| MEM-08.2 | VERIFIED JVM | Regressione mirata e build debug |

#### Risultati regressione MEM-08.2

- StartupMemoryProfilerTest: 27 PASS.
- MemoryDiagnosticsStoreTest: 46 PASS.
- PumpEventsMetadataTest: 7 PASS.
- PumpEventsStreamingTest: 1 PASS.
- ChartDataCacheRepositoryTest: 12 PASS.
- CgmTimelineTest: 15 PASS.
- TimeEventTest: 7 PASS.
- DashboardEventTimelineTest: 6 PASS.
- PumpEventsAdapterParityTest: 1 SKIP.

Totale: 122 test, 121 PASS, 1 SKIP, 0 FAIL, 0 ERROR.
Test diagnostici: 73 PASS.
Git diff check: PASS.
Build assembleDebug offline: PASS.

Lo SKIP riguarda la precondizione del fixture locale
LocalAssets/pump_event per la parita Kotlin/Python.
Non costituisce una verifica di parita superata.
Non aggirare le precondizioni del test.
Completare la verifica prima della validazione definitiva A/B.

La regressione e mirata, non comprende l'intera suite.
PumpEventsMemoryBenchmarkTest non eseguito in questo checkpoint.

#### Benchmark sintetici JVM

| Metrica | Prima esecuzione | Consolidamento |
|---|---:|---:|
| Baseline ns/op | 12.815 | 13.076 |
| Profiler OFF ns/op | 27.960 | 23.293 |
| Profiler ON ns/op | 170.524 | 173.260 |
| CPU thread ON ns/op | 156.25 | 156.25 |
| Heap delta OFF byte | 0 | 229848 |
| Heap delta ON byte | 36700160 | 7809832 |
| Crescita heap osservata ON byte | 36700160 | 9767960 |
| Storico campioni | 512 | 512 |
| Storico fasi | 128 | 128 |
| Record CSV | 3200 | 3200 |
| Dimensione CSV byte | 280490 | 280490 |
| Scrittura CSV record/s | 5899.99 | 5785.05 |
| Record persi | 0 | 0 |

Le misure sono preliminari e riferite alla JVM desktop.
Il benchmark ON/OFF usa campioni sintetici.
La misura CPU riguarda il thread chiamante.
La misura CPU OFF ha risoluzione insufficiente.
Le misure heap includono allocazioni temporanee e GC.
Non rappresentano la memoria stabilmente trattenuta.
Il picco osservato non rappresenta il massimo assoluto.
Il benchmark I/O usa batch controllati.
Non dimostra assenza di perdite in saturazione continua.

#### Configurazione diagnostica implementata

- Switch persistente, default OFF.
- Campionamento attivo previsto ogni 250 ms.
- Campionamento idle previsto ogni 5 secondi.
- PSS con intervallo minimo previsto di 2 secondi.
- Storico RAM: massimo 512 campioni e 128 fasi concluse.
- Massimo 64 fasi contemporaneamente attive.
- Coda archivio limitata a 128 elementi.
- Archivio privato CSV con limite 5 MiB.
- Retention nominale 48 ore.
- Manutenzione periodica ogni 15 minuti.
- Reset limitato ai dati diagnostici.
- Export dei record completi non scaduti.
- Nessun nuovo GC forzato introdotto dal profiler.

La retention effettiva puo essere inferiore a 48 ore
per effetto del limite dimensionale.
La cancellazione fisica dipende dalla manutenzione,
dallo stato del processo e dalla disponibilita del filesystem.

#### Limiti e rischi ancora aperti

- Validazione scheduler, PSS, CPU, RAM e I/O su Android.
- Verifica persistenza dello switch e comportamento della GUI.
- Verifica visualizzazione percentuale del picco heap.
- Verifica copertura completa delle fasi operative richieste.
- Verifica reset ed export in presenza di errori Android.
- Reset non persistente attraverso una terminazione del processo.
- Possibile perdita di record e correlazioni con coda satura.
- Verifica contatore record persi ed errori archivio.
- Verifica recupero dopo terminazione reale del processo.
- Verifica assenza di impatti sui dati Tandem.
- Parita Kotlin/Python con fixture locale ancora da eseguire.
- OOM TS-001 non risolto e non verificato.

#### Roadmap operativa residua

| Gate | Stato | Obiettivo |
|---|---|---|
| MEM-09 | DONE | Consolidamento documentale locale |
| MEM-10 | PLANNED | Verifica APK e preparazione test Android |
| MEM-11 | PLANNED | Validazione funzionale profiler su HONOR 9X |
| MEM-12 | PLANNED | Baseline memoria con profiler OFF e ON |
| R2 | IN_PROGRESS | Prototipo isolato architettura A/B |
| R3 | PLANNED | Migrazione cache con recupero e rollback |
| R4 | PLANNED | Integrazione A/B dopo superamento dei gate |
| R5 | PLANNED | Parita dati, regressione e prestazioni |
| R6 | PLANNED | Validazione dispositivo e download completo |
| R7 | PLANNED | Consolidamento finale TS-001 |

MEM-10 e MEM-11 richiedono verifica delle condizioni
di installazione e autorizzazione esplicita prima
di qualsiasi aggiornamento APK sul dispositivo.

MEM-12 deve registrare almeno:

- Heap Java utilizzato, committed e massimo.
- Memoria nativa e PSS.
- Baseline idle con profiler OFF e ON.
- Caricamento cache e preparazione dashboard.
- Autenticazione e download pump_logs.
- Parsing, estrazione, merge e salvataggio.
- Picchi campionati, durata e delta delle fasi.
- Record diagnostici persi ed errori I/O.
- Eventuali OOM, crash o anomalie funzionali.

Non cancellare dati, cache o credenziali Tandem.
Non trasferire payload sanitari o dati identificativi.
Non incrementare la versione senza autorizzazione.

L'architettura A/B rimane la soluzione pianificata
per ridurre l'occupazione RAM della gestione dello storico.

Il Memory Profiler e uno strumento di misura,
non costituisce la risoluzione dell'OOM TS-001.


### TS-001 - Consolidamento Crash Diagnostics MEM-11.2f

Stato complessivo: IN_PROGRESS.
Implementazione locale consolidata; validazione Android ancora necessaria.

#### Contesto

- MEM-11.2: FAIL sul dispositivo HONOR 9X STK-LX1, Android 10.
- Heap growth limit: 384 MiB.
- Processo terminato durante la verifica di startup.
- OutOfMemoryError rilevato, stack trace utile non disponibile.
- Picco Java osservato: 232590 KiB; non rappresenta il picco reale.
- La causa applicativa precisa dell'OOM non e ancora dimostrata.
- TS-001 rimane IN_PROGRESS.

#### Implementazione Crash Diagnostics

- Handler delle eccezioni Java non gestite.
- Inizializzazione anticipata in MainActivity.
- Delega all'handler precedente mediante finally.
- Funzionamento indipendente dal Memory Profiler.
- Ultima fase nota mediante whitelist.
- Header con timestamp, fase, classe eccezione e heap Java.
- Sincronizzazione del file prima dell'estrazione dello stack.
- Stack frame con classe, metodo e numero di riga.
- Nessuna registrazione dei messaggi delle eccezioni.
- Nessuna registrazione dei nomi dei file sorgente.
- Report privato latest_crash.txt.
- Dimensione massima: 65536 byte.
- Riserva heap best effort: 65536 byte.
- Massimo 64 frame per causa e 4 cause.
- Riserva di 256 byte per contatori e stato finale.
- FRAMES_WRITTEN riferito ai frame effettivamente scritti.
- RECORD_COMPLETE=NO per troncamenti rilevati.
- Retention 48 ore, verificata all'inizializzazione e alla lettura.
- Lettura limitata e sanitizzazione prima dell'esportazione.
- Pulsante Export crash report in Memory Diagnostics.
- Esportazione manuale mediante Storage Access Framework.
- Nessun nuovo endpoint o telemetria.

#### Difetto rilevato e corretto

MEM-11.2f.4d: 102 PASS, 1 FAIL su 103 test.

Il test writerRejectsMalformedFrames ha rilevato che un frame
contenente newline interrompeva il ciclo, impedendo la
registrazione dei successivi frame validi.

Correzione MEM-11.2f.4d.2:

- Controllo anticipato delle lunghezze e dei caratteri CR/LF.
- Scarto del singolo frame non valido mediante continue.
- Conservazione del break per il limite dimensionale.
- Nessuna modifica dei criteri di accettazione.

MEM-11.2f.4d.3: difetto risolto nei test JVM.

#### Evidenze di verifica

| Suite | Test | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|---:|
| StartupMemoryProfilerTest | 27 | 27 | 0 | 0 | 0 |
| MemoryDiagnosticsStoreTest | 46 | 46 | 0 | 0 | 0 |
| CrashDiagnosticsTest | 18 | 18 | 0 | 0 | 0 |
| CrashDiagnosticsWriterTest | 12 | 12 | 0 | 0 | 0 |
| Totale | 103 | 103 | 0 | 0 | 0 |

- Build debug offline: PASS dopo l'ultima modifica applicativa.
- Diff check: PASS.
- Working tree check: PASS.
- Test writer con OOM sintetico: PASS.
- OOM con heap realmente esaurito: NOT_EXECUTED.
- Validazione GUI Android: NOT_EXECUTED.
- APK aggiornato non ancora installato.
- Nessun incremento versione, commit o push.

#### Rischi residui e limiti

- La diagnostica fatal e best effort.
- Il rilascio della riserva heap non garantisce memoria disponibile.
- L'estrazione dello stack trace puo richiedere ulteriori allocazioni.
- La fase globale rappresenta l'ultima fase nota, non necessariamente
  quella del thread responsabile del crash.
- Crash nativi, terminazioni LMK ed eccezioni intercettate
  dall'applicazione non sono necessariamente registrati.
- La delega all'handler Android originale non e verificata sul device.
- Crash concorrenti possono interrompere la scrittura del report.
- I controlli sintattici non garantiscono la provenienza degli
  identificativi degli stack frame.
- La retention non garantisce cancellazione esattamente alla scadenza
  se l'applicazione non e in esecuzione.
- L'esportazione GUI deve essere verificata sul dispositivo.
- Il report non costituisce una soluzione dell'OOM originale.

#### Roadmap operativa MEM-11.2f

| Checkpoint | Attivita | Stato |
|---|---|---|
| MEM-11.2f.1 | Crash handler | DONE |
| MEM-11.2f.2 | Startup e copertura fasi | DONE |
| MEM-11.2f.3 | Retention, lettura, sanitizzazione, GUI | DONE |
| MEM-11.2f.4a | Verifica sorgenti | VERIFIED |
| MEM-11.2f.4b | Formattazione | VERIFIED |
| MEM-11.2f.4c | Writer e contatori | VERIFIED |
| MEM-11.2f.4d.1 | Analisi difetto writer | VERIFIED |
| MEM-11.2f.4d.2 | Correzione difetto writer | VERIFIED |
| MEM-11.2f.4d.3 | Regressione 103 test JVM | VERIFIED |
| MEM-11.2f.4e | Consolidamento documentazione locale | DONE |
| MEM-11.2f.5a | Build e verifica APK | PLANNED |
| MEM-11.2f.5b | Installazione previa autorizzazione | PLANNED |
| MEM-11.2f.5c | Riproduzione startup e acquisizione crash | PLANNED |
| MEM-11.2f.5d | Analisi stack e validazione diagnostica | PLANNED |

#### Gate successivi

1. Build offline e verifica APK, versione, package, firma e SHA256.
2. Autorizzazione esplicita prima dell'installazione.
3. Aggiornamento conservativo senza cancellazione dati.
4. Riproduzione del problema e acquisizione della sola diagnostica.
5. Verifica dello stack trace, se disponibile.
6. Aggiornamento evidenze e decisione sulla prosecuzione.

La roadmap A/B resta distinta dalla diagnostica.
R2 rimane IN_PROGRESS; R3-R7 non risultano completati.
MEM-12 resta BLOCKED fino al superamento del gate startup.
Nessuna dichiarazione di risoluzione TS-001 prima della
validazione funzionale e della memoria sul dispositivo.

### TS-001 - Evidenze Android MEM-11.2f.6

Data: 2026-11-24.
Stato TS-001: IN_PROGRESS.

#### Installazione e verifica APK

- Build debug offline: PASS.
- Test diagnostici JVM: 103/103 PASS.
- Package: com.example.tandemapp.st.dev.
- VersionName: 26.11.002, invariata.
- VersionCode: 2611002, invariato.
- APK: TandemSourceRT.apk.
- Dimensione: 23825552 byte.
- SHA256: 34CDC30F873501CA07C4DCBB1BC22F540E4B82A49E9D6F98934808206F383145.
- Firma Android Debug: verificata e compatibile.
- Installazione tramite adb install -r: PASS.
- SHA256 APK installato: corrispondente.
- FirstInstallTime invariato.
- Nessuna disinstallazione o cancellazione intenzionale dei dati.
- Integrita completa dei dati applicativi non verificata.

#### Problemi operativi risolti

- Identificazione APK mediante output-metadata.json.
- Parser certificati compatibile con Build Tools 37.0.0.
- Connessione ADB ripristinata mediante riavvio del server.
- Nessuna modifica ai driver Windows.

#### Evidenze sul dispositivo reale

Dispositivo: HONOR 9X, STK-LX1, Android 10.

- Avvio controllato: processo inizialmente stabile per 90 secondi.
- Nessun riavvio osservato durante il monitoraggio.
- Successiva terminazione del processo.
- Crash report persistente recuperato dopo la terminazione.
- Tempo dal lancio al crash: circa 140.56 secondi.
- Dimensione report: 584 byte.
- Report completo: RECORD_COMPLETE=YES.
- Ultima fase registrata: json_parse.
- Eccezione: java.util.concurrent.TimeoutException.
- Java used: 402629840 byte.
- Java committed: 402653184 byte.
- Java max: 402653184 byte.
- Margine heap residuo: 23344 byte.
- Occupazione heap: circa 99.994 percento.
- Stack frame acquisiti: 7/7.
- Righe diagnostiche accettate: 19/19.
- Righe escluse dal filtro: 0.

Stack trace:

    java.lang.Object.wait:-2
    java.lang.Object.wait:442
    java.lang.ref.ReferenceQueue.remove:190
    java.lang.ref.ReferenceQueue.remove:211
    java.lang.Daemons$FinalizerDaemon.runInternal:271
    java.lang.Daemons$Daemon.run:137
    java.lang.Thread.run:929

#### Interpretazione e limiti

VERIFIED:

- Saturazione quasi totale dell'heap Java.
- Registrazione persistente di un crash reale.
- Recupero del report dopo terminazione del processo.
- Acquisizione di eccezione, stack trace, fase e memoria.
- Nessun payload sanitario presente nel report acquisito.

NON DIMOSTRATO:

- Relazione causale diretta tra saturazione heap e TimeoutException.
- Allocazione applicativa esatta responsabile della saturazione.
- Corrispondenza tra ultima fase globale e thread terminato.
- Risoluzione dell'OOM originale.
- Funzionalita completa della GUI.
- Corretto funzionamento del Memory Profiler su Android.
- Affidabilita del crash handler in ogni condizione OOM.
- Integrita completa dei dati dopo aggiornamento APK.

La TimeoutException non deve essere classificata come
OutOfMemoryError. La saturazione dell'heap e' invece misurata
direttamente e costituisce una criticita verificata.

L'ultima fase json_parse e' un indicatore diagnostico globale,
non una prova che il thread terminato stesse eseguendo il parser.

#### Decisione operativa

Sospendere ulteriori riproduzioni del crash con l'architettura
corrente. Conservare cache e report diagnostico esistenti.

Dare priorita alla revisione delle allocazioni JSON e al
prototipo isolato A/B prima di ulteriori integrazioni.

Non utilizzare System.gc(), Thread.sleep() o aumento del limite
heap come soluzione architetturale alla saturazione.

#### Roadmap consolidata

Questo aggiornamento prevale sui precedenti stati dei
checkpoint qui elencati.

| Checkpoint | Stato | Evidenza o prossima attivita |
|---|---|---|
| MEM-11.2f.5a | VERIFIED | APK, firma e compatibilita |
| MEM-11.2f.5b | VERIFIED | Installazione conservativa |
| MEM-11.2f.5c | VERIFIED | Processo stabile nei primi 90 s |
| MEM-11.2f.5d | BLOCKED | Baseline memoria non acquisita |
| MEM-11.2f.5d.1 | VERIFIED | Report crash reale completo |
| MEM-11.2f.6 | DONE | Consolidamento evidenze Android |
| MEM-12 | BLOCKED | Riprendere dopo stabilizzazione |
| R2 | IN_PROGRESS | Revisione allocazioni e prototipo A/B |
| R3 | PLANNED | Migrazione conservativa |
| R4 | PLANNED | Integrazione architettura A/B |
| R5 | PLANNED | Integrita, parita e prestazioni |
| R6 | PLANNED | Verifica dispositivo reale |
| R7 | PLANNED | Consolidamento finale |

#### R2 - Prossime attivita prioritarie

1. Inventario delle allocazioni nel percorso json_parse.
2. Identificazione delle strutture residenti e temporanee.
3. Verifica delle copie complete e dei riferimenti trattenuti.
4. Revisione del prototipo A/B gia disponibile.
5. Parsing streaming con memoria limitata.
6. Scrittura progressiva nello slot inattivo.
7. Validazione completa prima del cambio generazione.
8. Test sintetici di integrita, memoria e interruzione.
9. Misurazione del picco durante aggiornamento e memoria a regime.

Obiettivi sperimentali, non ancora verificati:

- Heap Java a regime inferiore a 40 MiB.
- Picco heap durante aggiornamento inferiore a 120 MiB.
- Ultimi tre giorni elaborati residenti in RAM.
- Storico precedente disponibile su disco.
- Conservazione completa della retention configurata.
- Nessuna perdita di originali, indici o metadati Tandem.
- Nessuna pubblicazione di generazioni incomplete.

R3 e R4 restano subordinati alla validazione del prototipo R2.
TS-001 rimane IN_PROGRESS.

### TS-001 - Consolidamento prototipo R2.3.2

Data: 2026-11-24.

Stato TS-001: IN_PROGRESS.
Stato R2: IN_PROGRESS.

Questa sezione consolida le evidenze successive a MEM-11.2f.6.
Per i checkpoint R2 riportati prevale sulle precedenti indicazioni
di stato. Non modifica i requisiti approvati.

#### R2.1 - Analisi allocazioni

Stato: DONE.

- Il percorso operativo legge direttamente lo stream HTTP.
- Il parser operativo materializza comunque l'intero albero JSON.
- PumpEventsSource conserva il payload originale materializzato.
- Il dataset precedente e quello in aggiornamento possono
  coesistere in memoria.
- Ordinamento, DTO, raggruppamenti, merge e retention producono
  ulteriori strutture e allocazioni.
- La cache attuale conserva il dataset elaborato e le sorgenti.
- Non sono disponibili misure affidabili per attribuire una
  quantita precisa di heap a ogni struttura.
- Il crash durante json_parse non dimostra da solo quale
  allocazione abbia causato il problema.

#### R2.2 - Revisione implementazione esistente

Stato: DONE.

- Verificato il precedente decoder streaming.
- Verificati i test della cache e della sostituzione atomica.
- Non individuato un gestore completo di generazioni A/B
  nell'implementazione applicativa esaminata.
- Lo store diagnostico non costituisce uno storico A/B.

#### R2.3.1 - Archivio payload originali

Stato: VERIFIED.

Sorgente: PumpPayloadArchive.kt.
Test: PumpPayloadArchiveTest.kt.

- Copia progressiva con buffer da 64 KiB.
- SHA-256 calcolato incrementalmente.
- Limite dimensionale esplicito.
- Rifiuto di input vuoto e letture senza avanzamento.
- File temporaneo nella directory di destinazione.
- Flush e sincronizzazione prima della sostituzione.
- Sostituzione atomica del file di destinazione.
- Conservazione del file precedente in caso di errore
  prima della sostituzione.
- Il chiamante mantiene la responsabilita dello stream.
- Fedelta dei byte verificata con dati sintetici.
- Sorgente sintetica da 32 MiB verificata.

Evidenza: 11/11 test superati.

La validazione JSON e la durabilita della directory dopo
perdita di alimentazione non sono dimostrate.
L'archivio non costituisce ancora una generazione A/B valida.

#### R2.3.2 - Parser incrementale

Stato: VERIFIED per il prototipo isolato.

Sorgente: PumpPayloadStreamParser.kt.
Test: PumpPayloadStreamParserTest.kt.

- R2.3.2a: callback incrementale per singolo evento.
- R2.3.2b.1: limite lessicale prima della materializzazione
  di token eccessivamente grandi.
- R2.3.2b.2: limiti numero e dimensione nomi top-level.
- R2.3.2b.3a: eliminazione array UTF-8 temporanei.
- R2.3.2b.3b: eliminazione serializzazione temporanea
  completa del singolo elemento.

Tutti i checkpoint sopra indicati: VERIFIED.

Proprieta verificate:

- Decodifica UTF-8 con rifiuto sequenze malformate.
- Parsing JSON strict.
- Conservazione ordine originale eventi.
- Indice originale progressivo Long.
- Conservazione campi sconosciuti nel singolo elemento.
- Callback separata per metadati top-level.
- Rifiuto membri duplicati.
- Rifiuto documenti troncati o con contenuto successivo.
- Limiti espliciti di profondita, nodi e dimensioni.
- Conteggio UTF-8 senza array temporaneo.
- Conteggio dimensione serializzata senza String completa.
- Nessuna conservazione dell'intero array events nel parser.

Limiti predefiniti sperimentali:

- Profondita massima: 64.
- Dimensione massima elemento: 1 MiB.
- Nodi massimi per elemento: 16384.
- Membri top-level massimi: 256.
- Budget nomi top-level: 64 KiB.

Il superamento dei limiti deve invalidare la generazione
candidata senza troncare o perdere silenziosamente dati.

I limiti devono essere verificati rispetto alla compatibilita
dei payload prima dell'integrazione operativa.

Le callback sono provvisorie fino alla validazione completa
del documento. Nessuna callback autorizza da sola il commit
di una generazione.

Il payload originale archiviato rimane la sorgente autorevole.

Evidenza: 52/52 test parser superati.

#### R2.3.2b.4a - Benchmark ordinario

Stato: VERIFIED.

Sorgente: PumpPayloadStreamBenchmarkTest.kt.

| Eventi | Payload byte | Esito |
| ---: | ---: | --- |
| 2000 | 403856 | PASS |
| 20000 | 4077856 | PASS |
| 100000 | 20477856 | PASS |
| 1000000 | 206777856 | PASS |

Regressione mirata: 68/68 test superati.
Heap massimo JVM: 512 MiB.

Scenario da un milione di eventi:

- Tempo: 1886.5045 ms.
- Throughput: 530080.90 eventi/s.
- Picco heap osservato: 326934200 byte.

Il benchmark ordinario condivide la JVM tra i test.
Le condizioni iniziali non sono uniformi.
Il picco comprende oggetti non ancora recuperati dal GC.

Il primo errore di estrazione XML non era un errore del parser.
Recupero metriche e ripristino benchmark completati.

#### R2.3.2b.4b - Benchmark con heap limitato

Stato: VERIFIED.

Quattro esecuzioni indipendenti della JVM di test.

Ogni scenario elabora:

- 1000000 eventi sintetici.
- 206777856 byte di payload.
- 3 membri metadata.
- Checksum indici: 499999500000.

| Heap massimo MiB | Heap iniziale MiB | Picco MiB | Heap finale MiB | Tempo ms | Eventi/s | Esito |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 384 | 36.27 | 52.44 | 16.83 | 2210.7885 | 452327 | PASS |
| 128 | 15.64 | 31.53 | 11.22 | 2217.8426 | 450889 | PASS |
| 64 | 17.78 | 22.97 | 8.78 | 2360.0053 | 423728 | PASS |
| 32 | 12.02 | 19.99 | 15.03 | 2372.7817 | 421446 | PASS |

Picchi osservati in byte:

- 384 MiB: 54988536.
- 128 MiB: 33063864.
- 64 MiB: 24086160.
- 32 MiB: 20959912.

Tutti i limiti effettivi della JVM sono stati verificati.
Quattro scenari eseguiti e quattro superati.

Ordine, indici, conteggi e checksum verificati.
Nessun evento perso nei controlli effettuati.

Il throughput a 32 MiB diminuisce di circa il 6.83%
rispetto allo scenario a 384 MiB.

Il minimo limite verificato e 32 MiB.
Non rappresenta il minimo limite teoricamente possibile.

Il limite riguarda l'intera JVM desktop di test.
Non rappresenta una misura del solo parser o dell'app Android.

La generazione del payload precede la misura del parsing.
Il picco campionato non rappresenta necessariamente
il picco assoluto.

Il benchmark non include estrazione dataset completo,
ordinamento, indicizzazione, archivio A/B o GUI.

I metadati sono verificati per conteggio e ordine dei nomi.
Non costituisce una prova completa di parita semantica.

Nessun GC forzato introdotto nel benchmark.
Configurazione Gradle temporanea rimossa.
Nessuna modifica permanente alla configurazione Gradle.

#### R2.3.2b.4c - Consolidamento e regressione

Stato: VERIFIED.

Regressione completa del 2026-11-24:

- Classi test: 19.
- Test complessivi: 250.
- Test superati: 249.
- Failure: 0.
- Error: 0.
- Skip: 1.
- Esito: PASS_WITH_KNOWN_SKIP.
- Build debug offline: PASS.
- Integrita hash checkpoint precedenti: PASS.
- Working tree invariato durante i test: PASS.

Lo skip noto riguarda PumpEventsAdapterParityTest:
Kotlin adapter matches Python adapter on local pump event fixture.

La parita coperta da tale test non risulta verificata.

R2.3.2b.4c.1: VERIFIED.

Il controllo BOM precedente ha prodotto un falso positivo
per confronto Unicode dipendente dalla cultura.

Verificato TODO UTF-8 senza BOM, EOL LF e terminatore
finale LF singolo. Nessuna conversione di codifica.

R2.3.2b.4c.2: DONE.

Aggiornamento documentale con conservazione dei byte
originali e verifica dei report della regressione precedente.

Nessuna ripetizione non necessaria dei test.
Nessuna nuova installazione APK.
Nessuna modifica del percorso operativo.
Nessun incremento versione.
Nessun accesso intenzionale ai dati applicativi.
Nessun commit o push.

#### Roadmap R2 consolidata

| Checkpoint | Attivita | Stato |
| --- | --- | --- |
| R2.1 | Analisi allocazioni | DONE |
| R2.2 | Revisione implementazione esistente | DONE |
| R2.3.1 | Archivio payload originali | VERIFIED |
| R2.3.2a | Parser incrementale | VERIFIED |
| R2.3.2b.1 | Protezione token | VERIFIED |
| R2.3.2b.2 | Protezione metadati | VERIFIED |
| R2.3.2b.3a | Eliminazione array temporanei | VERIFIED |
| R2.3.2b.3b | Eliminazione String temporanea | VERIFIED |
| R2.3.2b.4a | Benchmark ordinario | VERIFIED |
| R2.3.2b.4b | Benchmark heap limitato | VERIFIED |
| R2.3.2b.4c | Consolidamento e regressione | VERIFIED |
| R2.3.3 | Indicizzazione su disco | PLANNED |
| R2.3.4 | Elaborazione dataset a memoria limitata | PLANNED |
| R2.3.5 | Gestore generazioni A/B | PLANNED |
| R2.3.6 | Recupero dopo interruzione | PLANNED |
| R2.3.7 | Accesso storico e finestra RAM | PLANNED |
| R2.3.8 | Benchmark integrato e regressione | PLANNED |
| R2.4 | Validazione complessiva prototipo | PLANNED |

#### R2.3.3 - Indicizzazione su disco

Stato: PLANNED.

| Checkpoint | Attivita | Stato |
| --- | --- | --- |
| R2.3.3a | Formato e contratto indici | PLANNED |
| R2.3.3b | Scrittura incrementale | PLANNED |
| R2.3.3c | Lettura puntuale | PLANNED |
| R2.3.3d | Provenienza payload verificabile | PLANNED |
| R2.3.3e | Integrita e interruzioni | PLANNED |
| R2.3.3f | Benchmark memoria e prestazioni | PLANNED |
| R2.3.3g | Regressione e consolidamento | PLANNED |

Prossimo checkpoint: R2.3.3a.

Prima dell'implementazione definire formato, contratto
di scrittura, lettura, validazione e gestione errori.

Requisiti:

- Scrittura progressiva degli indici.
- Nessuna lista completa degli indici residente in RAM.
- Conservazione eventIndex originale senza rinumerazione.
- Identificazione verificabile del payload di provenienza.
- Associazione coerente con payloadIndex.
- Conservazione metadati temporali originali.
- Conservazione sequenceNumber e sequenceGroup.
- Conservazione deviceAssignmentId e clockChanges.
- Conservazione molteplicita CGM.
- Distinzione dato originale, normalizzato e visualizzato.
- Accesso puntuale agli eventi originali verificabile.
- Rifiuto indici non validi, incompleti o incoerenti.
- Nessuna pubblicazione di indici provvisori.
- Nessuna modifica cache operativa durante il prototipo.
- Nessuna dipendenza da dati sanitari reali per i test.

Gli offset, se utilizzati, devono riferirsi ai byte del
payload originale, non ai caratteri decodificati o al
read-ahead del parser.

Il formato definitivo non e ancora approvato.

Non assumere che un indice posizionale sia sufficiente
per ordinamento temporale e raggruppamenti semantici.

#### Rischi residui e verifiche obbligatorie

- Payload con eventi o metadati oltre i limiti sperimentali.
- Errori di indicizzazione e associazione alla sorgente.
- Perdita campi sconosciuti o molteplicita CGM.
- Alterazione ordinamento stabile originale.
- Raggruppamenti bolus distribuiti lungo lo storico.
- Stato basal e suspend/resume tra eventi distanti.
- Clock changes e discontinuita temporali.
- Coesistenza generazioni attiva e candidata.
- Spazio disco per archivio, indici e dataset.
- Interruzioni durante scrittura, validazione e pubblicazione.
- Lettori concorrenti durante il cambio generazione.
- Durabilita manifest e recupero dopo crash.
- Migrazione senza perdita della cache precedente.
- Retention AppConfig.HISTORY_DAYS indipendente dalla RAM.
- Accesso storico oltre i tre giorni residenti.
- Overhead complessivo memoria, CPU e I/O su Android.
- Integrita e parita del dataset elaborato.
- Verifica Android del problema OOM originale.

Obiettivi applicativi sperimentali invariati:

- Heap Java stabile inferiore a 40 MiB.
- Picco aggiornamento inferiore a 120 MiB.

Nessuno dei due obiettivi e ancora verificato sull'app.

#### Stato roadmap generale TS-001

| Fase | Stato |
| --- | --- |
| R2 - Prototipo isolato | IN_PROGRESS |
| R3 - Migrazione cache | PLANNED |
| R4 - Integrazione operativa | PLANNED |
| R5 - Parita e prestazioni | PLANNED |
| R6 - Validazione Android | PLANNED |
| R7 - Consolidamento finale | PLANNED |
| MEM-12 - Baseline applicativa | BLOCKED |

La risoluzione del crash Android non e ancora verificata.

Il report MEM-11.2f.6 rimane evidenza del problema originale.
Non ripetere il crash senza una nuova motivazione tecnica.
Non cancellare cache o dati applicativi per aggirare il problema.

Il prototipo resta separato dal percorso operativo fino
al completamento dei gate di compatibilita e integrita.

### TS-001 - MEM-13: priorita diagnostica

Stato: IN_PROGRESS.

La dashboard si blocca dopo il login e successivamente l'app
termina. L'utente non ha ancora potuto utilizzare il monitor.

Priorita: rendere accessibile il monitor e misurare le fasi
applicative prima di scegliere ulteriori modifiche allo storico.

- R2.3.1 e R2.3.2: evidenze del prototipo preservate.
- R2.3.3 e successive attivita architetturali: sospese.
- Nessuna implementazione SQLite o confronto parallelo.
- Nessuna modifica retention, cache o percorso di parsing operativo.

| Checkpoint | Attivita | Stato |
| --- | --- | --- |
| MEM-13.1 | Analisi navigazione e avvio | DONE |
| MEM-13.2 | Monitor nella barra inferiore | IN_PROGRESS |
| MEM-13.3 | Avvio senza caricamento automatico | IN_PROGRESS |
| MEM-13.3a | Capacita e pressione memoria dispositivo/app | IN_PROGRESS |
| MEM-13.4 | Test e build offline (dopo MEM-13.3a) | VERIFIED |
| MEM-13.5 | Installazione autorizzata e verifica GUI | IN_PROGRESS |
| MEM-13.6 | Misure a riposo e durante caricamento | VERIFIED |
| MEM-13.7 | Identificazione fase critica cache_decode | VERIFIED |
| MEM-14.1 | Inventario cache senza materializzazione | VERIFIED |
| MEM-14.2 | Analisi allocazioni e crescita storico | IN_PROGRESS |
| MEM-14.3 | Soluzione mirata e test sintetici | IN_PROGRESS |
| MEM-14.4 | Regressione, build e verifica Android autorizzata | PLANNED |

MEM-13.2 e MEM-13.3: sorgenti pubblicati e riletti su GitHub,
non ancora compilati o verificati su Android.

Ordine barra: Login, Dashboard, Calendar, Pump, Sensor Set, Monitor.
Riutilizzare MemoryDiagnosticsScreen.
Preferenza automatic_data_load persistente, default OFF.
Remember me indipendente. Sign in conserva il caricamento manuale.

Verificare avvio senza lettura cache o download automatico,
accesso Monitor, persistenza preferenza, navigazione e caricamento
manuale. Verificare esportazione misure precedenti e crash report.
Non assumere che il monitor resti reattivo durante saturazione heap.
Non cancellare cache o dati. MEM-12 resta BLOCKED fino a baseline.

#### MEM-13.3a - Capacita memoria e pressione del sistema

Requisiti approvati dall'utente. Completare prima di MEM-13.4,
in modo da effettuare un unico ciclo di build e installazione.

- Device memory: ActivityManager.MemoryInfo.totalMem, availMem,
  threshold e lowMemory. RAM totale utilizzabile dal sistema,
  non necessariamente RAM fisica nominale. Mostrare disponibile,
  occupazione stimata e soglia critica.
- Application memory: Runtime.maxMemory(), heap utilizzato
  (totalMemory - freeMemory), heap committed, percentuale,
  margine residuo e picco registrato. Mostrare anche native heap
  allocato e PSS del processo, senza sommarli all'heap Java.
- Grafico heap con limite massimo e storico RAM disponibile
  del dispositivo, confrontabili sulla stessa base temporale.
- Campionamento heap 250 ms durante attivita, parametri di
  sistema e PSS ogni 2 s; preservare politica idle esistente.
  Nessun campionamento periodico aggiuntivo con profiler OFF.
- Conservare misure di sistema nello storico esportabile,
  incluse condizioni iniziali e misure precedenti al crash.
  Mantenere compatibilita con CSV precedenti, distinguere
  valori mancanti da zero, mantenere retention 48 h e cap 5 MiB.
- Distinguere saturazione heap applicativo, pressione memoria
  del sistema e crescita PSS/native; indicatori diagnostici,
  non soglie di stabilita garantita. Il margine heap non
  garantisce il successo della prossima allocazione.
- Nessuna raccolta di dati sanitari, credenziali o identificativi.
  Nessuna modifica al limite heap Android, largeHeap, cache,
  retention applicativa o percorso di parsing operativo.

Verifiche: test sintetici di calcolo percentuali e margini,
valori mancanti, compatibilita storico/export, limiti archivio,
campionamento e overhead ON/OFF. Build offline e verifica Android
di accesso Monitor, valori coerenti con strumenti di sistema,
baseline a riposo e misure durante caricamento manuale.
Confrontare esecuzioni con diversa RAM disponibile senza
attribuire causalita al solo valore di RAM libera.
Il limite heap di 384 MiB osservato su HONOR 9X e un'evidenza
precedente, non un valore da codificare nel software.

Stato: IN_PROGRESS. Sorgenti pubblicati; test e build non eseguiti.

Evidenze WIP MEM-13.3a:
- 9592796: estensione CSV a 18 campi; export record legacy a 14
  campi con quattro campi vuoti aggiunti, archivio invariato.
- 2ee41c2: acquisizione MemoryInfo insieme al PSS.
- c407511: GUI capacita, margine, percentuali e grafici.
- fc875f2: test sintetico export misto legacy/nuovo.
- Restano da verificare build, regressione, calcoli, campionamento,
  overhead e comportamento Android. Con profiler OFF i valori
  campionati sono storici o assenti. A riposo vale intervallo 5 s.
- Nessuna modifica cache operativa o versione; nessuna installazione.

#### MEM-13.4 - Evidenza regressione e build

- Prima regressione su e4950a5: 251 test, 246 PASS, 4 FAIL,
  1 SKIP. Controllo comune CSV ancora a 14 campi.
- Correzione b76fb08541146768694074c02b48a289d98feefd:
  assertEquals(18, fields.size), nessuna modifica applicativa.
- Sincronizzazione locale fast-forward PASS.
- Regressione offline: 251 test, 250 PASS, 0 FAIL, 0 ERROR,
  1 SKIP noto (fixture locale parity).
- assembleDebug offline PASS.
- APK: app/build/outputs/apk/debug/TandemSourceRT.apk
- Versione: 26.11.002; dimensione: 24249964 byte.
- SHA256: 2CACD09712DB6E2B5D0B08B608DD06E919556CB37B88A70DADF52047EF2F8CBA
- APK non installato. MEM-13.5 richiede autorizzazione.
- MEM-13.2, MEM-13.3 e MEM-13.3a compilati; verifica funzionale
  Android ancora necessaria. Test specifici di calcolo,
  frequenza e overhead Android ancora da completare.
- Nessuna evidenza di risoluzione OOM. MEM-12 resta BLOCKED.

#### MEM-13.5 - Installazione e prima verifica GUI

- Installazione adb -r PASS su STK-LX1, 25/11/2026 09:52:37.
- firstInstallTime invariato; nessuna cancellazione dati.
- Monitor accessibile, switch ON, grafici e controlli visibili.
- Screenshot a riposo: RAM totale 3679.9 MiB, disponibile
  1249.4 MiB, threshold 216 MiB, lowMemory false.
- Heap 6.0-6.6 MiB su 384 MiB, picco 8.5 MiB,
  committed 11.1 MiB, native 18.7 MiB, PSS 49.9-52.8 MiB.
- Prima baseline visiva positiva; durata, export, persistenza
  switch dopo riavvio e overhead non ancora verificati.
- Richiesta GUI: rimuovere pulsante Back ridondante nel Monitor.
  Conservare navigazione di sistema e barra inferiore.
  Intervento PLANNED, non richiede reinstallazione immediata.
- MEM-13.5 IN_PROGRESS; MEM-13.6 prima baseline visiva acquisita.
- Non cancellare statistiche prima di esportare le evidenze.

#### MEM-13.6/7 - Evidenza 25/11/2026

Due sessioni distinte, ore 10:22 e 10:30, 101 e 97 record
diagnostici. Entrambe completano assignments, download/decode,
json_parse, event_extraction e assignment_merge. cache_decode
ha START senza END in entrambi i CSV acquisiti.

| Misura | 10:22 | 10:30 |
| --- | --- | --- |
| Download/decode principale (ms) | 6442 | 7531 |
| JSON parse (ms) | 2426 | 3394 |
| Event extraction (ms) | 3505 | 3667 |
| Assignment merge (ms) | 82 | 56 |
| Heap max campionato (byte) | 402653184 | 402653176 |
| Limite heap (byte) | 402653184 | 402653184 |
| RAM disponibile minima campionata (byte) | 1020686336 | 1247145984 |
| Margine ultimo campione (byte) | 8 | 8 |

LowMemory false nei campioni di sistema disponibili.
ADB successivo: processo assente. Saturazione heap durante
cache_decode riprodotta; eccezione finale e singole allocazioni
non identificate. Non dedurre assenza assoluta di pressione
sistema dai soli campioni. Non ripetere il crash.

#### MEM-14 - Priorita correzione cache

Analisi sorgente ChartDataCacheRepository.kt (blob
6a2a86841cbebb56278deb03acd349e8fdc700c3):
mergeForDisplay chiama initialize prima di compattare incoming.
readEnvelope materializza ChartDataCache completo tramite
decodeFromStream, inclusi DayDataset e pumpEventsSources.
Streaming del file non implica memoria limitata del dataset.
retainWindow filtra serie ma non eventi nei payload originali.
compactSources conserva interi payload referenziati o con
clockChanges; retainCgmOriginals accumula sorgenti distinte.
Ipotesi da verificare: crescita di payload sovrapposti conservati
tra aggiornamenti. Nessuna prova ancora del contributo relativo.
readEnvelope usa runCatching anche per errori fatali e GC/sleep
dopo decode; non adottarli come rimedio OOM.

MEM-14.1: misurare dimensioni cache e marker senza leggere
contenuto sanitario; inventario strutturale locale in streaming
con soli conteggi e byte aggregati, nessun payload esportato.
MEM-14.2: verificare modello e serializzatori, moltiplicita,
riferimenti, clockChanges, crescita per aggiornamento e
allocazioni con fixture sintetiche.
MEM-14.3: scegliere intervento dopo evidenze; preservare
provenienza, retention e cache precedente, evitare perdita
silenziosa in caso di errore. Nessun aumento heap o reset cache.
MEM-14.4: regressione e build offline, prova Android autorizzata
con confronto baseline e picco; non dichiarare OOM risolto
prima della verifica.

R2.3.3+ restano sospesi. MEM-12 resta BLOCKED. Nessuna modifica
applicativa in questo aggiornamento. MEM-13.5 resta IN_PROGRESS
per persistenza switch, export e verifiche GUI residue.

#### MEM-14.1 - Evidenza inventario cache 25/11/2026

- Cache 69651864 byte (66.425 MiB), ultima modifica 17/11 07:28.
- Marker p0c2b-v3-days-7, retention configurata 7 giorni.
- Scansione strutturale streaming locale ADB: PASS, 23.77 s,
  SIZE_MATCH YES, nessun payload salvato, dispositivo invariato.
- pumpEventsSources: 17 payload, 66038721 byte (94.81%).
- events: 187484 elementi, 66017995 byte; clockChanges:
  67 elementi, 18923 byte (sottoinsiemi delle sorgenti).
- CGM: 9849 elementi, 2939159 byte; supplementalEvents:
  1776 elementi, 504286 byte; basal 1820/105186;
  iob 866/41179; deviceStates 68/11827; bolus 71/10505;
  carbs 18/847 (conteggi/byte).
- La predominanza dei payload originali su disco e verificata;
  contributo alle allocazioni heap e sovrapposizione tra payload
  da misurare. Scansione non equivalente a validazione JSON completa.
- MEM-14.2: misurare sovrapposizioni per sorgente e riferimenti
  CGM, preservando moltiplicita e clockChanges; solo aggregati
  locali, nessuna deduplicazione o modifica cache in questa fase.

#### MEM-14.2 - Evidenza sovrapposizione payload 25/11/2026

- Scansione locale PASS; 17 payload, 2 gruppi assignment,
  nessun assignment sconosciuto. Nessun dato originale salvato
  o modifica dispositivo.
- 187484 eventi, 149874 sovrapposti (79.94%), 37610 distinti
  per assignment secondo hash JSON normalizzato e massima
  molteplicità osservata; nessun duplicato interno.
- 9849 punti CGM, tutti con provenienza; 9849 riferimenti
  distinti, nessun indice invalido (verifica strutturale).
- 10 payload senza riferimenti CGM, tutti con clockChanges;
  7 payload referenziati. 67 clockChanges complessivi.
- Confermata sovrapposizione tra snapshot e condizione di
  conservazione dei payload non referenziati in compactSources.
- Non equivale a prova di equivalenza semantica o autorizzazione
  a deduplicare. Conservare eventIndex, payloadIndex, metadati
  temporali, molteplicità e originali. Non stimare heap futuro
  dalla sola percentuale di sovrapposizione.
- MEM-14.2 resta IN_PROGRESS: analisi allocazioni sintetiche,
  sovrapposizione temporale e verifica alternative conservative.
- Valutare separazione fisica dei payload originali dalla cache
  elaborata, con accesso su richiesta; nessuna scelta definitiva
  o modifica applicativa. R2.3.3+ restano sospesi.

#### MEM-14.3 - Direzione approvata 25/11/2026

Separare persistenza dei payload originali e cache elaborata,
caricando gli originali solo su richiesta. Nessuna deduplicazione,
SQLite o indice binario in questa fase. MEM-14.2 resta aperto
per verifiche sintetiche delle allocazioni.

| Sottofase | Attivita | Stato |
| --- | --- | --- |
| MEM-14.3a | Inventario consumatori dei payload e contratto dati | IN_PROGRESS |
| MEM-14.3b | Prototipo separazione e lettura selettiva | PLANNED |
| MEM-14.3c | Migrazione streaming non distruttiva e recovery | PLANNED |
| MEM-14.3d | Test sintetici integrita, memoria e guasti | PLANNED |
| MEM-14.3e | Integrazione percorso operativo | PLANNED |

Preservare originali, ordine, eventIndex, payloadIndex verificabile,
clockChanges, molteplicità CGM e retention. Migrazione senza
materializzare la vecchia cache intera, mantenendo il file
precedente fino a validazione e pubblicazione recuperabile.
Verificare tutti i consumatori prima di cambiare il modello.
Nessun formato definitivo scelto. Misurare heap con fixture
sintetiche comparabili ai 17 payload / 187484 eventi osservati.
Verificare anche download, merge e save: separare solo la lettura
non garantisce memoria limitata durante gli aggiornamenti.
Nessuna modifica applicativa, installazione o reset autorizzato
in questo checkpoint documentale.

#### MEM-14.3a - Verifica contratto e prototipi

Inventario git grep fornito dall'utente su riferimento locale;
HEAD locale b76fb08, nessuna modifica tracciata riportata.
Models.kt e CgmTimeline.kt riletti sul branch remoto: timeline
usa requestedAssignmentId e clockChanges, non array events.
Adapter, repository e cache richiedono adattamento del contratto.
Preservare assenza rispetto a JsonNull esplicito di clockChanges.
PumpPayloadArchive riutilizzabile: buffer 64 KiB, SHA256, limite
byte, fsync file e move atomico; non garantisce commit multi-file.
PumpPayloadStreamParser riutilizzabile: callback per evento con
indice Long originale, metadati, limiti per elemento, risultati
provvisori fino a validazione completa. Richiede array events;
compatibilita con payload legacy da verificare. Accesso per indice
tramite scansione completa, senza indice binario iniziale.
Non confondere integrita JSON logica della cache legacy con byte
originali HTTP, non disponibili nella cache serializzata.
Proposta descrittore leggero: assignment, clockChanges, riferimento
archivio, conteggio eventi e integrita; nessun formato definitivo.
Prima del prototipo verificare test esistenti e contratto di
pubblicazione, errori e limiti aggregati. Nessun sorgente modificato.

#### MEM-14.3b - Prototipo descrittore e accesso selettivo

- MEM-14.3b.1 DONE: PumpPayloadDescriptor.kt, commit 41d4e44.
- MEM-14.3b.2: 7/8 PASS, fallimento round-trip clockChanges assente/null.
- MEM-14.3b.2a VERIFIED: rappresentazione interna lista 0/1 elementi;
  correzione 8468e11, adeguamento test 1a0541b.
  Output locale utente: HEAD 1a0541b2fa2a21ae7059901c2129fc344be60c7f,
  8 test, 0 failure/error/skip, assembleDebug offline PASS.
  Nessuna installazione o modifica dispositivo riportata.
- MEM-14.3b.3 PLANNED: benchmark sintetico in JVM isolata, confronto
  descrittore e accesso selettivo con dimensioni crescenti, heap e tempi;
  verificare integrita e memoria senza dati reali. Nessuna conclusione
  Android o integrazione applicativa dai soli test unitari.

#### MEM-14.3b.3a - VERIFIED benchmark descrittore

Output locale utente: 12/12 configurazioni PASS, integrita PASS,
cleanup PASS, nessun dato reale o modifica dispositivo.
Primo tentativo interrotto dal controllo XML PowerShell dopo test PASS;
lettura InnerText corretta nello script successivo.
Payload sintetici 10000/100000/1000000 eventi, rispettivamente
2027856/20477856/206777856 byte. Heap JVM 384/128/64/32 MiB.
Picchi osservati describe/readEvent (byte), ordine heap decrescente:
- 10000: 41517816/77646488; 38515160/74342504;
  40097720/42900312; 21913000/25825752.
- 100000: 246978248/245921224; 86033536/86169984;
  47238888/47358784; 27204224/27637408.
- 1000000: 248596080/248708184; 87129424/87250720;
  47210688/47337152; 27596144/27731144.
Tempi 1M describe/readEvent ms: 2221.3885/1954.8112;
2259.295/1966.8499; 2335.1156/2020.8222; 2382.7478/2079.467.
Picchi campionati intera JVM, non live set o limite assoluto;
generazione sintetica prima delle misure, nessun GC forzato.
Accesso singolo con scansione completa; non dimostrata parita
applicativa, memoria aggregata, migrazione o comportamento Android.
Due warning nullable nel benchmark da correggere prima consolidamento.
Prossimo: contratto migrazione streaming e pubblicazione recuperabile,
verifica test esistenti, limiti aggregati e regressione sintetica.

#### MEM-14.3c.2a - VERIFIED

HEAD locale 4c8ff413460f0eb90ce1ec869ee2d0f3d7c44264.
LegacyCacheExtractorTest: 10 test, 0 failure, 0 error, 0 skip.
assembleDebug offline PASS. Nessun dato reale o modifica dispositivo.
Estrattore JSON streaming senza SQLite, non integrato nell'app.
Conservazione logica verificata su fixture sintetica, non identita byte.
Aperti: validazione semantica riferimenti CGM, budget aggregati,
payload senza events (attualmente rifiutati), directory temporanee JUnit,
manifest, pubblicazione e recovery. Prima della pubblicazione completare
validazione e limiti, poi benchmark migrazione completa.

#### MEM-14.3c.2b.1 - VERIFIED

HEAD locale 1d8fb46fbf3a809f5b3ac159229a1eaef0b32b56.
LegacyCgmValidatorTest 10/10, LegacyCacheExtractorTest 10/10,
zero failure/error/skip; assembleDebug offline PASS.
Nessun dato reale utilizzato, nessuna modifica al dispositivo.
Validazione batch dei riferimenti originali e SHA-256 verificata su fixture sintetiche.
Restano aperti timestamp elaborato, is_recovered, limiti aggregati,
integrazione estrattore/validatore, gestione payload senza events,
collocazione temporanei, pubblicazione e recovery. Nessuna prova OOM risolta.

#### MEM-14.3c.2b.3 - VERIFIED

HEAD locale b3bd4acad7f4e3b3f3b85185d8d579e14b94ff63.
Regressione offline: 284 test, 282 PASS, 0 failure, 0 error, 2 skip attesi
(PumpEventsAdapterParityTest fixture locale assente e PumpPayloadDescriptorBenchmarkTest opt-in).
LegacyCgmValidatorTest 14/14, LegacyCacheExtractorTest 10/10, assembleDebug PASS.
Proiezione CGM originale condivisa con adapter: timestamp e is_recovered verificati
su fixture sintetiche. Nessuna modifica dispositivo. Nessuna prova OOM risolta.
Prossimo MEM-14.3c.2c: budget aggregati, limiti prima delle allocazioni/scritture,
limiti descrittori e metadati, test di superamento e cleanup. Restano aperti
integrazione, payload senza events, temporanei, pubblicazione e recovery.

#### MEM-14.3c.2c - VERIFIED

HEAD locale d093856ca77a7007b2492d9dfba60f1572d10cf0.
Test mirati offline: estrattore 17/17, validatore CGM 14/14, nuovi budget 7/7 (inclusi nei 17), zero failure/error/skip; assembleDebug PASS.
Limiti provvisori: input 512 MiB, payload 256 MiB, dataset 32 MiB, output aggregato 512 MiB, riserva disco 16 MiB, metadati serializzati 4 MiB, 500000 nodi dataset, 100000 CGM, 128 sorgenti. Verificati rifiuto e cleanup con input sintetici invariati. Temporanei estrattore sotto build.
Non misurato heap effettivo della migrazione; budget serializzati non equivalgono a heap. Singolo descrittore costruito prima del controllo aggregato. Riserva disco non esclusiva. Regressione completa non rieseguita in questo incremento. Nessuna modifica dispositivo.
Prossimo MEM-14.3c.2d: integrare estrattore e validatore in staging non pubblicato, verificando limiti prima della deserializzazione e integrita' dei riferimenti.

#### MEM-14.3c.2d - VERIFIED

HEAD locale 72057505f58648489229ca0fa061adff8510827e.
Regressione offline: 300 test, 298 PASS, zero failure/error, 2 skip attesi (PumpEventsAdapterParityTest e PumpPayloadDescriptorBenchmarkTest). Migrazione 9/9, estrattore 17/17, validatore CGM 14/14; assembleDebug PASS. Nessuna modifica dispositivo.
Coordinatore di staging non pubblicato: parita logica JSON, serie, riferimenti CGM, molteplicità, metadati e cleanup verificati su fixture sintetiche. Restano aperti payload senza events, locking, manifest, pubblicazione atomica, recovery e misura heap della migrazione completa.
Prossimo MEM-14.3c.3: manifest e pubblicazione atomica, senza modificare cache operativa o marker legacy.

#### MEM-14.3c.3 - VERIFIED (test JVM e build)

HEAD locale 1fc9b0aca242e2a4487be80835d1e6b02388b3df.
Manifest, persistenza e pubblicazione atomica, lock writer cooperativo.
Test mirati 17/17 (manifest 8, pubblicazione 5, concorrenza 4).
Regressione offline 317 test, 315 PASS, zero failure/error,
2 skip attesi (parity fixture locale e benchmark opt-in).
assembleDebug offline PASS (45 s), APK 23426407 byte,
SHA256 3CF59BF2FF5132F87B43C022041F1D5C6E4BF8F850175FAEA9F70FF7F8ACF6D8.
Stato locale invariato secondo output utente. Nessuna installazione.
Sync directory simulata nei test; durabilita Android e concorrenza
tra processi non verificate. Lock cooperativo, repository legacy
non ancora integrato. Verificare anche semantica lock POSIX quando
un secondo channel sullo stesso file viene chiuso.
Prossimo: MEM-14.3c.4 recovery e lettura limitata del puntatore/manifest,
validazione generazione, gestione non distruttiva degli orfani.
Restano benchmark migrazione completa, warning nullable, integrazione
operativa e verifica Android. MEM-12 resta BLOCKED; OOM non risolto.
