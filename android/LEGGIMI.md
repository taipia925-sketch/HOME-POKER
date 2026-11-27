# Home Poker per Android (app + widget)

L'app Android **è la stessa Home Poker**: apre il sito con Chrome a schermo intero (Trusted Web Activity),
quindi si resta collegati con il proprio account, e notifiche e Face ID funzionano come nel browser.
In più ha il **widget** per la schermata Home.

## Cosa mostra il widget
- Serata in corso: montepremi, giocatori, cash o torneo (tocco → apre la serata).
- Altrimenti la prossima serata: giorno, ora, posti presi, luogo (tocco → apre la prenotazione).
- «Ti devono» e «Devi» su tutti i tuoi tavoli.
- Messaggi non letti della chat (tocco sul bollino → apre la chat).
- Si aggiorna da solo ogni 30 minuti, quando apri l'app e quando tocchi «aggiornato alle…».
- Due misure: piccola (2×1) e grande (4×2), si adatta quando lo ridimensioni.

## Come si collega all'account
La web app, quando gira dentro l'app Android, chiede al server un gettone personale (`hpl_widget_token`)
e lo passa all'app con `homepoker://widget?t=…`. Il widget usa solo quel gettone per leggere il riassunto
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

## Barra dell'indirizzo
Per nasconderla, Android controlla il file `assetlinks.json` (in questa cartella) all'indirizzo
`https://taipia925-sketch.github.io/.well-known/assetlinks.json`. Va pubblicato nel repository
`taipia925-sketch.github.io` (con un file vuoto `.nojekyll`). Finché manca, l'app funziona lo stesso
ma mostra una sottile barra con l'indirizzo in alto.
