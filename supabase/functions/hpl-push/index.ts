// Supabase Edge Function "hpl-push": manda le notifiche di Home Poker ai telefoni del tavolo.
import webpush from "npm:web-push@3.6.7";
import { createClient } from "npm:@supabase/supabase-js@2.58.0";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });

webpush.setVapidDetails(
  Deno.env.get("VAPID_SUBJECT") ?? "mailto:homepoker@example.com",
  Deno.env.get("VAPID_PUBLIC")!,
  Deno.env.get("VAPID_PRIVATE")!,
);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
    const { data: u } = await db.auth.getUser(jwt);
    const uid = u?.user?.id;
    if (!uid) return json({ error: "not_signed_in" }, 401);

    const { table_id, to, title, body, url, tag, test } = await req.json();
    if (!table_id) return json({ error: "bad_request" }, 400);

    // chi manda deve essere nel tavolo
    const { data: mem } = await db.from("hpl_members").select("uid").eq("uid", uid).eq("table_id", table_id).maybeSingle();
    if (!mem) return json({ error: "not_member" }, 403);
    const { data: dev } = await db.from("hpl_devices").select("account_id").eq("uid", uid).maybeSingle();

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
    }
    if (!accounts.length) return json({ sent: 0, subs: 0, failed: [] });

    const { data: subs } = await db.from("hpl_push_subs").select("endpoint, p256dh, auth").in("account_id", accounts);
    const payload = JSON.stringify({
      title: String(title ?? "Home Poker").slice(0, 90),
      body: String(body ?? "").slice(0, 220),
      url: typeof url === "string" ? url : "./",
      tag: typeof tag === "string" ? tag : undefined,
    });
    let sent = 0;
    const failed: { host: string; code: number | null; msg: string }[] = [];
    await Promise.all((subs ?? []).map(async (s) => {
      const host = (() => { try { return new URL(s.endpoint).host; } catch { return "?"; } })();
      try {
        // "high": sui telefoni Android (Samsung soprattutto) senza priorità alta il risparmio batteria trattiene le notifiche
        await webpush.sendNotification({ endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } }, payload, { TTL: 86400, urgency: "high" });
        sent++;
      } catch (e) {
        const err = e as { statusCode?: number; body?: string; message?: string };
        const code = err.statusCode ?? null;
        console.error("push failed", host, code, String(err.body ?? err.message ?? e).slice(0, 300));
        failed.push({ host, code, msg: String(err.body ?? err.message ?? "").slice(0, 160) });
        if (code === 404 || code === 410) await db.from("hpl_push_subs").delete().eq("endpoint", s.endpoint);
      }
    }));
    return json({ sent, subs: (subs ?? []).length, failed });
  } catch (e) {
    console.error("hpl-push", e);
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
