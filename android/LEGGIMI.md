# Home Poker per Android (app + widget)

L'app Android è un **programma vero** (WebView, niente Chrome): dentro gira lo stesso sito di Home Poker.
La prima volta si entra con nome utente e PIN, poi si resta collegati. In più ha il **widget** per la schermata Home
e le **notifiche** (chat, serate iniziate o programmate, soldi da dare o avere), controllate ogni 15 minuti.
Foto, messaggi vocali e file esportati (salvati nei Download) funzionano dentro il programma.

## Cosa mostra il widget
- Serata aperta del tavolo: montepremi e lista dei giocatori con quanto hanno messo e quanto devono ancora pagare.
  Chi tiene il banco fa il **re-buy con un tocco** («+ € 20», poi «Confermi?») e può fare **Annulla** per 10 minuti.
  Le modifiche finiscono nella cronologia della serata, come quelle fatte dall'app. Gli altri vedono la lista in sola lettura.
- Altrimenti la prossima serata: giorno, ora, posti presi, luogo (tocco → apre la prenotazione).
- «Ti devono» e «Devi» su tutti i tuoi tavoli.
- Messaggi non letti della chat (tocco sul bollino → apre la chat).
- Si aggiorna da solo ogni 30 minuti, quando apri l'app e quando tocchi «aggiornato alle…».
- Due misure: piccola (2×1) e grande (4×2), si adatta quando lo ridimensioni.

## Come si collega all'account
La web app, quando gira dentro il programma (`window.HPAndroid`), chiede al server un gettone personale (`hpl_widget_token`)
e lo passa al programma con `HPAndroid.link()`. Il widget usa solo quel gettone per leggere il riassunto
dalla funzione `hpl-widget`: non vede il PIN né la sessione. `hpl_widget_revoke` lo annulla.

## Compilare
Servono JDK 17, Android SDK 35 e Gradle 8.14.

```
cd android
export HP_KEYSTORE=/percorso/homepoker.jks HP_KEYSTORE_PASS=… HP_VERSION_CODE=2 HP_VERSION_NAME=1.1
gradle assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../home-poker.apk
```

La chiave di firma **non è nel repository**: è salvata nel database Supabase, tabella privata
`hpl_build_secrets` (`android_keystore_b64`, `android_keystore_pass`), leggibile solo dal server.
Gli aggiornamenti vanno firmati sempre con la stessa chiave, altrimenti il telefono non li installa sopra.

## Collegamento con il sito
Il file `assetlinks.json` (in questa cartella) è pubblicato in `https://taipia925-sketch.github.io/.well-known/assetlinks.json`
(repository `taipia925-sketch.github.io`). Fa aprire i link del sito direttamente nel programma e permette Face ID dentro l'app.
