// Supabase Edge Function "hpl-push": manda le notifiche di Home Poker ai telefoni del tavolo.
import { createClient } from "npm:@supabase/supabase-js@2.58.0";
import { cors, json, dropMuted, sendTo } from "../_shared/push.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
    const { data: u } = await db.auth.getUser(jwt);
    const uid = u?.user?.id;
    if (!uid) return json({ error: "not_signed_in" }, 401);

    const { table_id, to, title, body, url, tag, test, skip, mid } = await req.json();
    if (!table_id) return json({ error: "bad_request" }, 400);

    // chi manda deve essere nel tavolo
    const [{ data: mem }, { data: dev }] = await Promise.all([
      db.from("hpl_members").select("uid").eq("uid", uid).eq("table_id", table_id).maybeSingle(),
      db.from("hpl_devices").select("account_id").eq("uid", uid).maybeSingle(),
    ]);
    if (!mem) return json({ error: "not_member" }, 403);

    // a chi: tutto il tavolo (tranne chi manda), solo alcuni giocatori, oppure (prova) solo i propri telefoni
    let accounts: string[];
    if (test) {
      if (!dev?.account_id) return json({ sent: 0, subs: 0, failed: [] });
      accounts = [dev.account_id];
    } else {
      const { data: links } = await db.from("hpl_account_tables").select("account_id, player_id").eq("table_id", table_id);
      let targets = (links ?? []).filter((l) => l.account_id !== dev?.account_id);
      if (Array.isArray(to)) targets = targets.filter((l) => to.includes(l.player_id));
      accounts = [...new Set(targets.map((t) => t.account_id))];
      if (typeof tag === "string" && tag.startsWith("chat-")) {
        accounts = await dropMuted(db, table_id, accounts);
        // chi ha la chat aperta sullo schermo in questo momento vede già il messaggio: niente notifica
        if (Array.isArray(skip)) accounts = accounts.filter((a) => !skip.includes(a));
      }
    }
    return json(await sendTo(db, table_id, accounts, { title, body, url, tag, mid: test ? undefined : mid }));
  } catch (e) {
    console.error("hpl-push", e);
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
