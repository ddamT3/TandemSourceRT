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

### TS-004 — Evidenziazione dei reminder in scadenza o scaduti
- **Stato:** `PLANNED`
- **Obiettivo:** rendere immediatamente riconoscibili i reminder previsti per oggi o già scaduti.
- **Comportamento:** se la data del reminder è uguale o precedente alla data locale odierna del dispositivo, visualizzare l'intera riga in rosso e grassetto (bold); se la data è futura, mantenere lo stile normale.
- **Confronto date:** considerare solo la data locale del dispositivo, senza confrontare l'ora.
- **Aggiornamento UI:** rivalutare lo stile quando si apre la schermata e al cambio di giornata, senza richiedere modifiche ai dati salvati.
- **Verifiche:** test mirati su reminder futuri, odierni e scaduti, incluso cambio di data.
- **Accettazione:** tutti e soli i reminder con data ≤ oggi risultano rossi e in grassetto; nessuna modifica ai dati persistenti.

### TS-005 — Ricerca automatica degli aggiornamenti
- **Stato:** `PLANNED`
- **Obiettivo:** sostituire la ricerca manuale delle nuove versioni con il solo flag `Check for updates automatically`.
- **Flag OFF:** nessuna ricerca automatica.
- **Flag ON:** verificare la disponibilità di aggiornamenti tramite il meccanismo GitHub Releases già presente, al massimo una volta ogni 24 ore; memorizzare la data/ora dell'ultimo controllo riuscito per evitare controlli inutili dopo il riavvio.
- **Priorità:** eseguire il controllo in background a bassa priorità, senza ritardare autenticazione, download, elaborazione o visualizzazione dei dati Tandem; evitare verifiche contemporanee.
- **UI:** rimuovere il pulsante `Check for updates`; quando esiste una versione più recente, mostrare il numero versione (es. `26.11.005`) e abilitare `Download new ver`; altrimenti nascondere il pulsante di download.
- **Download:** solo su azione esplicita dell'utente; nessuna installazione automatica.
- **Integrazione TS-002:** attivare o disattivare il flag non deve causare la navigazione involontaria alla Dashboard; verificare e correggere il sintomo descritto in TS-002.
- **Verifiche:** test mirati su flag OFF/ON, intervallo di 24 ore, avvio, rete assente, versione più recente/non presente, comportamento non bloccante, assenza di navigazione involontaria.
- **Accettazione:** comportamento coerente con il flag, frequenza massima rispettata, dati Tandem prioritari, disponibilità corretta del download e nessuna installazione automatica.

## Rischi trasversali
- Integrità e provenienza dei dati CGM/Tandem e riferimenti `payloadIndex`.
- OOM, cache obsolete, duplicazioni e blocchi UI.
- Privacy sanitaria e segreti nei log.
- Regressioni Android e differenze fra dispositivi.
- File temporanei, backup e diagnostica persistente non necessari.
- Divergenze tra working tree locale e GitHub.
- Reminder non aggiornati al cambio di data e regressioni nella navigazione dal Login.
- Verifiche di aggiornamento che interferiscono con i dati Tandem o effettuano download non richiesti.

## Cronologia verifiche
- 2026-11-22: identificato crash OOM tramite log ADB; **correzione NON ESEGUITA**.
- 2026-11-22: preparata riorganizzazione documentale; i test applicativi non sono pertinenti a questa modifica.
- 2026-11-22: concordate e documentate TS-004 e TS-005; **implementazione e test NON ESEGUITI**.
