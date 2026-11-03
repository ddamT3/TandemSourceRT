# Project Rules

## Regola obbligatoria: script PowerShell autoconsistenti

Quando viene richiesto uno script PowerShell per modificare, verificare o
gestire il progetto, fornire sempre uno script completo, autoconsistente
e direttamente eseguibile.

Requisiti obbligatori:

1. Ogni script deve poter essere copiato ed eseguito integralmente in
   una nuova sessione PowerShell, senza dipendere da variabili o comandi
   eseguiti in precedenza.
2. Non fornire patch parziali, frammenti da sostituire manualmente,
   istruzioni del tipo "modifica questa parte dello script precedente"
   o modifiche manuali a file sorgente.
3. In caso di errore in uno script precedente, fornire sempre una nuova
   versione completa e corretta dello script.
4. Definire esplicitamente percorsi, variabili, controlli preliminari
   e prerequisiti necessari.
5. Prima di modificare un file, verificare che la struttura attesa
   corrisponda a quella effettivamente presente.
6. Creare un backup dei file modificati prima della scrittura.
7. In caso di errore durante le modifiche o le verifiche, ripristinare
   i file modificati quando tecnicamente possibile.
8. Non eseguire installazioni, commit, push o modifiche alla release APK
   senza richiesta esplicita.
9. Per le modifiche al codice Android, eseguire la compilazione debug
   offline e i test pertinenti, quando disponibili.
10. Riportare chiaramente esito, file modificati, percorso dei backup
    ed eventuali errori.
11. Evitare modifiche non richieste e preservare le modifiche locali
    preesistenti.
12. Se uno script e' soltanto diagnostico, non modificare alcun file
    del progetto.

Questa regola si applica anche agli script forniti per correggere
errori introdotti da script precedenti.

<!-- TANDEMSOURCERT_RULES_V1 -->

## Regole operative specifiche TandemSourceRT

### Priorita' vincolanti

1. Preservare integrita', provenienza e correttezza dei dati Tandem.
2. Proteggere dati sanitari, credenziali e informazioni personali.
3. Prevenire OOM, regressioni e blocchi della UI.
4. Limitare gli interventi al task richiesto.
5. Preservare tutte le modifiche locali non correlate.

### Privacy e rete

- Consentire esclusivamente le comunicazioni applicative necessarie
  con i servizi Tandem gia' previsti dal progetto.
- Non introdurre telemetria, analytics, upload o endpoint aggiuntivi
  senza autorizzazione.
- Non condividere con agenti dati sanitari reali, credenziali,
  token, cache o payload HTTP.
- Utilizzare dati sintetici o anonimizzati nei test.
- Non riportare payload completi nei log diagnostici.

### Integrita' e provenienza dei dati

- Preservare eventIndex originale.
- Rimappare payloadIndex soltanto in modo verificabile.
- Preservare sequenceNumber, sequenceGroup, pumpDateTime,
  estimatedDateTime, egvTimeStamp e deviceAssignmentId.
- Preservare clockChanges e i campi sconosciuti dei payload.
- Non eliminare duplicati CGM senza verificarne la semantica.
- Distinguere dati originali, normalizzati e visualizzati.
- Non introdurre trasformazioni irreversibili non autorizzate.

### Cache, retention e memoria

- Centralizzare la retention tramite AppConfig.HISTORY_DAYS.
- Verificare separatamente download, storico, cache ed export.
- Non deserializzare cache obsolete prima dell'invalidazione.
- Preservare consistenza fra CGM e pumpEventsSources.
- Non creare riferimenti payloadIndex invalidi.
- Non eliminare clockChanges necessari alla timeline.
- Ridurre copie temporanee, grandi stringhe e payload duplicati.
- Non usare System.gc() o Thread.sleep() come soluzione agli OOM.
- Versionare o invalidare esplicitamente formati cache incompatibili.

### Script PowerShell autoconsistenti

1. Fornire sempre script completi, autonomi e pronti per un unico
   copia-incolla.
2. Non dipendere da variabili o comandi precedenti.
3. Non fornire patch parziali o frammenti da sostituire manualmente.
4. Se uno script fallisce, fornire una nuova versione completa.
5. Verificare file, percorsi e precondizioni prima delle modifiche.
6. Creare backup e prevedere rollback.
7. Preservare encoding, BOM, newline e indentazione.
8. Evitare dipendenze fragili da indentazione esatta.
9. Preferire operazioni idempotenti.
10. Verificare realmente exit code e risultati.
11. Non dichiarare PASS dopo un comando fallito.
12. Non modificare file non correlati al task.
13. Non eseguire automaticamente operazioni Git.
14. Non installare APK o modificare release senza autorizzazione.

### Modalita' di sviluppo

- Un solo obiettivo funzionale per intervento.
- Preferire modifiche limitate a uno o due file.
- Identificare dipendenze e test pertinenti prima di modificare.
- Non eseguire refactoring o riformattazioni non richieste.
- Non ampliare autonomamente il perimetro.
- Preferire PowerShell per modifiche semplici.
- Usare Codex o Work soltanto quando giustificato dalla complessita'.

### Test e verifiche

- Compilare normalmente con:
  .\gradlew.bat :app:assembleDebug --offline
- Eseguire test unitari pertinenti.
- Distinguere PASS, FAIL e NON ESEGUITO.
- Non considerare risolto un OOM sulla sola base della build.
- Verificare la provenienza dei CGM e la conservazione dei
  metadati originali.
- Non installare automaticamente il debug sui dispositivi.

### Git e identita'

- Tutte le operazioni Git sono eseguite dall'utente tramite PowerShell.
- Nessun commit, push, reset, merge o altra operazione Git automatica.
- Non modificare la configurazione Git globale.
- Per commit autorizzati, usare esclusivamente l'identita' locale:
  user.name = ddamT3
  user.email = ddamT3@users.noreply.github.com
- Verificare l'identita' prima di ogni commit autorizzato.

### Percorsi portabili

- Non introdurre percorsi assoluti specifici del PC nei sorgenti.
- Preferire percorsi relativi alla root del repository.
- Negli script usare TANDEMSOURCERT_ROOT o una root verificata.
- Costruire i percorsi tramite Join-Path o API equivalenti.
- Non introdurre dipendenze implicite dalla directory corrente.

### Consegna

- Riportare file modificati, verifiche eseguite e relativi esiti.
- Non dichiarare completate operazioni non eseguite.
- Fornire sempre gli script completi nella conversazione.
- Segnalare errori, rollback e problemi residui.

<!-- END_TANDEMSOURCERT_RULES_V1 -->

<!-- POWERSHELL_COMPOUND_BLOCKS_V1 -->

### PowerShell: blocchi condizionali e strutture composte

- Consegnare sempre l'intera struttura `if` / `elseif` / `else`
  nello stesso script autoconsistente.
- Non separare mai `else` dal relativo `if` in blocchi da
  copiare o eseguire distintamente.
- Applicare la stessa regola a `try` / `catch` / `finally`,
  `switch`, cicli e blocchi annidati.
- Non chiedere all'utente di completare manualmente una
  struttura PowerShell iniziata in un messaggio precedente.
- Racchiudere preferibilmente gli script in un unico blocco
  `& { ... }`, completo di tutte le parentesi di apertura
  e chiusura.
- Prima della consegna, verificare che tutte le strutture
  composte siano sintatticamente complete.
- Non fornire mai un ramo `else`, `catch` o `finally`
  come comando autonomo.
