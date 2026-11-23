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
- La strumentazione diagnostica e' temporanea e deve essere rimossa prima di produrre un APK.
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
