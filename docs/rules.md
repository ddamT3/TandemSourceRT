# RULES — TandemSourceRT

> Due sezioni: (1) regole specifiche TandemSourceRT, (2) regole generali valide per tutti i progetti. Fonte specifica: precedente `docs/rules.md` del branch `work/v02.01.008`. Fonte generale: `WorkApp3/docs/RULES.md`. Nessuna modifica ai dati applicativi.

## 1. Regole specifiche del progetto TandemSourceRT

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

### Test e verifiche

- Compilare normalmente con:
  .\gradlew.bat :app:assembleDebug --offline
- Eseguire test unitari pertinenti.
- Distinguere PASS, FAIL e NON ESEGUITO.
- Non considerare risolto un OOM sulla sola base della build.
- Verificare la provenienza dei CGM e la conservazione dei
  metadati originali.
- Non installare automaticamente il debug sui dispositivi.

### Versionamento, build e distribuzione Android

<!-- TANDEM_VERSION_POLICY_V1_START -->
## Versionamento applicativo

- Formato obbligatorio: `YY.MM.NNN`.
- `YY`: ultime due cifre dell'anno.
- `MM`: mese da `01` a `12`.
- `NNN`: progressivo mensile da `001` a `999`.
- Al cambio del mese il progressivo riparte da `001`.
- Esempi: `26.11.001`, `26.11.002`, `26.12.001`.

### Fonte canonica
- Unica fonte: `release_versionName` in `app/build.gradle.kts`.
- Gradle calcola `versionCode` Android dalla versione canonica.
- Runtime, packaging e distribuzione devono usare la stessa versione.
- L'eventuale versione dello schema database resta indipendente.

### Incremento e rilascio
- Il bump e' esplicito, una sola volta per nuovo rollout.
- Un retry dello stesso rollout non incrementa la versione.
- Build, avvio, login, commit e push non incrementano la versione.
- `buildAPK.bat` non esegue bump, commit, push o deploy.
- Bump, commit, push e deploy richiedono autorizzazione esplicita.
- Prima della delivery verificare la versione effettiva dell'APK.
- Sequenza: bump autorizzato, test, build, verifica APK,
  commit/push autorizzati, delivery autorizzata.
<!-- TANDEM_VERSION_POLICY_V1_END -->
- Non installare APK né modificare release senza richiesta esplicita.
- Per modifiche al codice Android, eseguire la build debug offline `./gradlew.bat :app:assembleDebug --offline` e i test pertinenti quando disponibili; non interpretare una build come prova di assenza di OOM.
- Verificare provenienza CGM, metadati originali, cache e compatibilità sul dispositivo.
- Non cancellare dati dell'app, cache operative o credenziali durante la diagnostica senza autorizzazione.

### Identità e percorsi TandemSourceRT
- Repository: `ddamT3/TandemSourceRT`; verificare sempre branch e remote effettivi.
- Per commit autorizzati verificare l'identità Git locale `ddamT3 <ddamT3@users.noreply.github.com>`, senza modificare la configurazione globale.
- Negli script usare `TANDEMSOURCERT_ROOT` oppure una root verificata; costruire i percorsi con `Join-Path`.
- Preferire modifiche dirette su GitHub tramite gli strumenti disponibili, sul branch di lavoro verificato, con commit WIP piccoli e tracciabili quando autorizzati. Non usare main per il lavoro in corso.
- Riservare PowerShell alle verifiche locali, sincronizzazione, test, build, prove sul dispositivo e modifiche non eseguibili direttamente su GitHub. Codex o Work soltanto quando la complessità lo giustifica.
- Prima di modificare file su GitHub verificare la presenza di modifiche locali non pubblicate; consolidarle con un WIP autorizzato oppure evitare sovrapposizioni. Non sovrascrivere il lavoro locale.
- L'autorizzazione esplicita a un task con commit/push WIP copre soltanto gli incrementi pertinenti sul branch di lavoro; non autorizza release, deploy, installazioni, bump, force push o pubblicazione di dati riservati. Verificare ogni SHA remoto e distinguere modifiche pubblicate da test effettivamente eseguiti.

## 2. Regole generali — valide per tutti i progetti

Queste regole sono derivate dai principi generali di `WorkApp3/docs/RULES.md` e si applicano a ogni progetto; le regole specifiche della sezione 1 aggiungono vincoli, senza autorizzare deroghe implicite.

### 2.1 Ambito e priorità
1. Operare solo sul compito richiesto. Priorità: integrità dei dati, recuperabilità, correttezza funzionale, usabilità; privacy e sicurezza rimangono vincoli obbligatori.
2. Prima di intervenire verificare requisiti, file, dipendenze, rischi, stato del repository e criterio di accettazione.
3. Preferire incrementi piccoli, normalmente 1–2 file, reversibili e verificabili. Evitare refactoring, formattazioni, dipendenze o modifiche collaterali.
4. In caso di conflitto, ambiguità sostanziale o rischio di perdita dati, fermarsi e descrivere il blocco.

### 2.2 Filesystem pulito, input e artefatti
1. Non sporcare HDD/SSD: preferire analisi in memoria e output in console; non creare log, backup, report, script o directory temporanee senza necessità documentata.
2. Per modifiche reversibili a documenti versionati, verificare lo stato Git e usare il recupero dalla versione precedente quando sufficiente. Per dati non ricostruibili o modifiche rischiose, prevedere backup/rollback proporzionati e autorizzati.
3. Non cancellare, spostare o sovrascrivere dati, backup, input e configurazioni non correlati. Verificare sempre destinazioni e precondizioni prima della scrittura.
4. Separare input e output; usare dati sintetici o autorizzati per test; non produrre artefatti persistenti se non indispensabili.
5. Non inserire nei sorgenti percorsi assoluti del PC; preferire root verificata, percorsi relativi o variabili d'ambiente documentate.
6. Preservare encoding, BOM, newline, indentazione e convenzioni locali.

### 2.2.7 Convenzioni di formato
1. Ogni repository deve dichiarare una politica esplicita per EOL e file binari mediante `.gitattributes`.
2. Preferire LF per sorgenti e documentazione multipiattaforma; CRLF per script Windows BAT, CMD e PowerShell. Le eccezioni devono essere motivate.
3. Dichiarare indentazione, charset e newline finale mediante `.editorconfig`, ove applicabile.
4. Usare un TAB per livello nei linguaggi compatibili; per Python usare quattro spazi per livello secondo PEP 8. Il tasto TAB dell'editor puo' inserire gli spazi previsti.
5. Non mescolare TAB e spazi per l'indentazione dello stesso linguaggio o file.
6. Preservare encoding, BOM e convenzioni dei file esistenti durante modifiche puntuali. Non effettuare normalizzazioni massive o riformattazioni collaterali senza autorizzazione.
7. Verificare con `git ls-files --eol` e `git check-attr` l'effetto delle regole; non modificare `core.autocrlf` globale.
### 2.3 PowerShell e strumenti
1. Per Windows fornire script PowerShell 5.1 **completi e autoconsistenti**, copiabili in una nuova sessione senza variabili precedenti; nessuna patch parziale o modifica manuale a script precedenti.
2. Preferire blocchi inline `& { ... }`; non creare file `.ps1` salvo richiesta esplicita.
3. Tenere insieme `if/elseif/else`, `try/catch/finally`, `switch` e cicli; verificare sintassi, quoting, prerequisiti, exit code e risultati.
4. Se uno script fallisce, fornire la versione completa corretta; non dichiarare PASS per un comando fallito.
5. Prima di modificare più file, verificare tutti gli ancoraggi; usare scritture sicure e rollback quando necessari.
6. Non installare o aggiornare strumenti e dipendenze implicitamente. Usare assistenti aggiuntivi solo se giustificato e nei limiti autorizzati.

### 2.4 Privacy e rete
1. Non trasferire dati riservati, personali, sanitari, token o credenziali a servizi esterni, agenti, log o commit senza autorizzazione specifica.
2. Limitare rete e dipendenze ai servizi già previsti; non introdurre telemetria o endpoint nuovi implicitamente.
3. Non esporre payload completi o percorsi personali nei report pubblicabili.

### 2.5 Test e risultati
1. Eseguire test mirati al delta, con timeout finiti; la regressione completa richiede richiesta o motivazione esplicita.
2. Distinguere `PASS`, `FAIL`, `SKIP` e `NON ESEGUITO`; non inventare risultati né trattare un controllo statico come prova completa.
3. Conservare le evidenze precedenti e indicare ambiente, limiti e rischi residui. Non modificare input operativi nei test senza autorizzazione.

### 2.6 Git e pubblicazione
1. Verificare repository, branch, HEAD, remote, working tree, staging e identità locale prima di operazioni Git.
2. Nessun commit, push, reset, merge, rebase, stash, force push, rilascio o deploy implicito; richiedono autorizzazione esplicita. Le operazioni Git locali sono eseguite dall'utente via PowerShell, secondo la politica del progetto.
3. Non presumere sincronizzazione tra copia locale e GitHub; preservare modifiche locali e staged non correlate.
4. Prima di pubblicare, controllare file e contenuti inclusi; non pubblicare segreti, cache o artefatti. Verificare lo SHA remoto dopo un'eventuale pubblicazione.
5. Tenere distinti modifica, test, commit, push, build, installazione e distribuzione.

### 2.7 Documentazione e tracciabilità
1. Redigere documenti e report in italiano, conservando invariati identificatori, API, codice e stringhe tecniche.
2. Aggiornare documenti tematici esistenti; evitare file ridondanti per singoli task.
3. Distinguere `PLANNED`, `IN_PROGRESS`, `BLOCKED`, `DONE`, `VERIFIED` e gli stati di pubblicazione/distribuzione.
4. Non presentare come attuale un audit storico; riportare data, ambiente, limiti e verifiche effettive.
5. SNAPSHOT, checkpoint e procedure di release solo se esplicitamente richiesti, preservando la tracciabilità delle modifiche.
