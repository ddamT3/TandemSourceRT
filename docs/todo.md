# TODO — TandemSourceRT

> Registro delle attività note. Gli stati descrivono evidenze disponibili, non attestano test o release non eseguiti.

## Convenzioni
- `PLANNED`: previsto; `IN_PROGRESS`: in lavorazione; `BLOCKED`: impedimento; `DONE`: implementato; `VERIFIED`: test pertinente superato.
- Aggiornare per incremento e distinguere implementazione, test, commit, push e installazione.
- Preservare tutti i PASS già raggiunti, senza inventarne di nuovi.

## Attività aperte

### TS-001 — OutOfMemoryError durante download eventi Tandem
- **Stato:** `PLANNED`
- **Evidenza:** HONOR 9X, `com.example.tandemapp.st.dev`, versione `26.11.002`; OOM con heap limitato a circa 384 MiB durante `pump_logs`.
- **Stack:** `PumpEventsRepository.downloadEvents()` → `getJson()` → `readText()`; `PumpEventsRepository.kt:163,186`.
- **Intervento:** analizzare dimensioni, copie, buffering e chiamanti; introdurre gestione a memoria limitata senza perdita di eventi o metadati; verificare retention e cache.
- **Verifiche:** test mirati sintetici, build debug offline quando applicabile, riproduzione sul dispositivo; nessuna cancellazione dati, reinstallazione o log persistente non autorizzati.
- **Accettazione:** assenza OOM sul caso riproducibile, completezza dati, UI responsiva, nessuna regressione.

### TS-002 — Navigazione inattesa dal Login
- **Stato:** `PLANNED`
- **Sintomo da verificare:** l'opzione «Check for updates automatically» potrebbe provocare il passaggio alla Dashboard.
- **Intervento:** riprodurre; analizzare eventi UI e auto-login; correggere solo se confermato; test mirati.
- **Accettazione:** il checkbox modifica la preferenza senza navigazioni impreviste.

### TS-003 — Consolidamento regole e documentazione
- **Stato:** `IN_PROGRESS`
- **Intervento:** organizzare `docs/rules.md` in sezione specifica e sezione comune; mantenere `docs/todo.md` aggiornato.
- **Verifica:** confrontare con la copia locale prima di sincronizzare per non perdere modifiche non pubblicate.

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
