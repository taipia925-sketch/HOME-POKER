// Supabase Edge Function "hpl-chat-reply": risposta (o ❤️) scritta direttamente dalla notifica, ad app chiusa.
// Chi risponde è riconosciuto dal gettone firmato che il server ha messo nella sua notifica.
import { createClient } from "npm:@supabase/supabase-js@2.58.0";
import { cors, json, readToken, dropMuted, sendTo } from "../_shared/push.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const { tk, text, react, mid } = await req.json();
    const who = await readToken(tk);
    if (!who) return json({ error: "bad_token" }, 401);
    const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
    const { data: link } = await db.from("hpl_account_tables").select("account_id").eq("account_id", who.a).eq("table_id", who.t).maybeSingle();
    if (!link) return json({ error: "not_member" }, 403);

    if (react) {
      const emoji = String(react).slice(0, 8);
      if (typeof mid !== "number") return json({ error: "bad_request" }, 400);
      const { data: msg } = await db.from("hpl_chat").select("id").eq("id", mid).eq("table_id", who.t).maybeSingle();
      if (!msg) return json({ error: "not_found" }, 404);
      const { error } = await db.from("hpl_chat_react").insert({ msg_id: mid, table_id: who.t, acct: who.a, emoji });
      if (error && !/duplicate/i.test(error.message)) throw error;
      return json({ ok: true });
    }

    const t = String(text ?? "").trim().slice(0, 2000);
    if (!t) return json({ error: "empty" }, 400);
    const body: Record<string, unknown> = { t };
    if (typeof mid === "number") {
      const { data: re } = await db.from("hpl_chat").select("id, author_name, kind, body, deleted").eq("id", mid).eq("table_id", who.t).maybeSingle();
      if (re && !re.deleted && re.kind === "text") body.re = { id: re.id, n: re.author_name, s: String(re.body?.t ?? "").slice(0, 80) };
    }
    const { data: row, error } = await db.from("hpl_chat").insert({ table_id: who.t, kind: "text", body, author_acct: who.a }).select("id, author_name").single();
    if (error) throw error;
    await db.from("hpl_chat_read").upsert({ table_id: who.t, acct: who.a, last_id: row.id, updated_at: new Date().toISOString() });

    // avvisa gli altri del tavolo, come un messaggio scritto dall'app
    const { data: links } = await db.from("hpl_account_tables").select("account_id").eq("table_id", who.t);
    const others = await dropMuted(db, who.t, [...new Set((links ?? []).map((l) => l.account_id).filter((a) => a !== who.a))]);
    const { data: tab } = await db.from("hpl_tables").select("name").eq("id", who.t).maybeSingle();
    const first = String(row.author_name ?? "").split(/\s+/)[0] || "Qualcuno";
    await sendTo(db, who.t, others, {
      title: "💬 " + first + " · " + (tab?.name ?? "Chat"),
      body: t.slice(0, 200),
      url: "./#go=chat&t=" + encodeURIComponent(who.t) + "&g=x",
      tag: "chat-" + who.t,
      mid: row.id,
    });
    return json({ ok: true, id: row.id });
  } catch (e) {
    console.error("hpl-chat-reply", e);
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
