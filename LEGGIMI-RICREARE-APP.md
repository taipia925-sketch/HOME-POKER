# Home Poker: come ricreare l'app identica

Questo pacchetto contiene **tutto il codice** di Home Poker. Chi lo riceve, persona o intelligenza artificiale, non deve riscrivere niente: deve solo rimettere in piedi gli stessi pezzi. Sotto ci sono l'elenco dei file e i passi da seguire.

## Cosa c'è nel pacchetto

| File | Cosa fa |
|---|---|
| `index.html` | **Tutta l'app**: HTML, CSS e JavaScript in un solo file (vanilla JS, nessun framework e nessuna compilazione). Contiene cassa delle serate, debiti, storico, classifica, sala giochi (Texas Hold'em, Omaha, blackjack, bot), chat, profilo, notifiche e Face ID (WebAuthn). |
| `sw.js` | Service worker: funzionamento offline, aggiornamenti, notifiche push con «Rispondi» e «❤️». |
| `manifest.webmanifest`, `icon.png`, `icon.svg` | Rendono l'app installabile sulla schermata Home (PWA). |
| `supabase/sql/schema.sql` | **Il database completo**: tabelle, sicurezza (RLS), funzioni, trigger, realtime, storage. |
| `supabase/sql/2026-10-20-sicurezza.sql` | Ultime correzioni di sicurezza (già comprese in `schema.sql`). |
| `supabase/functions/hpl-holdem/` | Server dei tavoli virtuali di poker: motore di gioco, turni, tornei. |
| `supabase/functions/hpl-push/` | Invio delle notifiche push ai telefoni del tavolo. |
| `supabase/functions/hpl-chat-reply/` | Risposte e «mi piace» dati direttamente dalla notifica. |
| `supabase/functions/hpl-news/` | Notizie dal mondo del poker. |
| `supabase/functions/_shared/push.ts` | Codice condiviso delle notifiche. |
| `.github/workflows/deploy-functions.yml` | Pubblica automaticamente le funzioni su Supabase. |

## Come è fatta

- **Frontend:** una PWA statica, pubblicata su GitHub Pages. Il file `index.html` è l'intera app.
- **Backend:** Supabase.
  - Postgres con RLS.
  - Auth anonima: ogni telefono riceve un utente anonimo, poi lo si collega all'account (nome utente più PIN) con le funzioni `hpl_register` e `hpl_login`.
  - Realtime.
  - Storage per foto e vocali della chat.
  - Edge Functions in Deno.
- **Dati delle serate:** stanno nella tabella `hpl_docs`, come documenti JSON con percorsi tipo `t/<tavolo>/games/<id>`, `t/<tavolo>/players/<id>` e `t/<tavolo>/settings/table`.

## Passi per ricrearla

1. **Supabase.** Crea un progetto nuovo. In *Authentication → Sign In / Providers* attiva **Anonymous sign-ins**.
2. **Database.** Apri l'*SQL Editor*, incolla tutto `supabase/sql/schema.sql` ed eseguilo.
3. **Chiavi delle notifiche.** Genera una coppia di chiavi VAPID con `npx web-push generate-vapid-keys`. Poi, in *Edge Functions → Secrets*, aggiungi:
   - `VAPID_PUBLIC`: la chiave pubblica;
   - `VAPID_PRIVATE`: la chiave privata;
   - `VAPID_SUBJECT`: per esempio `mailto:tuaemail@esempio.com`.

   `SUPABASE_URL` e `SUPABASE_SERVICE_ROLE_KEY` le mette Supabase da solo.
4. **Funzioni.** Pubblica le quattro funzioni con la Supabase CLI:
   ```
   supabase functions deploy hpl-holdem
   supabase functions deploy hpl-push
   supabase functions deploy hpl-news
   supabase functions deploy hpl-chat-reply --no-verify-jwt
   ```
   In alternativa puoi usare il workflow GitHub incluso. In quel caso serve il secret del repository `SUPABASE_ACCESS_TOKEN`, e nel file del workflow va sostituito `nqlywzjhxykrfyrpkjdw` con l'ID del nuovo progetto.
5. **Collegare l'app al nuovo progetto.** In `index.html` sostituisci:
   - l'indirizzo `https://nqlywzjhxykrfyrpkjdw.supabase.co` con quello del nuovo progetto;
   - la chiave `sb_publishable_…` con la *publishable key* del nuovo progetto (*Project Settings → API Keys*);
   - il valore di `VAPID_PUBLIC` con la chiave pubblica creata al passo 3.
6. **Pubblicare il sito.** Carica `index.html`, `sw.js`, `manifest.webmanifest`, `icon.png` e `icon.svg` su un hosting HTTPS qualsiasi (GitHub Pages, Netlify, Vercel…). L'HTTPS è obbligatorio per notifiche e Face ID.
7. **Provare.** Apri il sito dal telefono, aggiungilo alla schermata Home, registrati e crea un tavolo.

## Cosa NON è nel pacchetto (ed è giusto così)

- **I dati veri:** account, tavoli, serate e messaggi. Restano nel database attuale.
- **Le chiavi segrete:** VAPID privata e service role. Vanno create di nuovo nel nuovo progetto e non si condividono mai.
