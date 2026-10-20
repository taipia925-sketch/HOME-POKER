// Invio delle notifiche push, usato da hpl-push e da hpl-chat-reply.
import webpush from "npm:web-push@3.6.7";

webpush.setVapidDetails(
  Deno.env.get("VAPID_SUBJECT") ?? "mailto:homepoker@example.com",
  Deno.env.get("VAPID_PUBLIC")!,
  Deno.env.get("VAPID_PRIVATE")!,
);

export const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
export const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });

// gettone per rispondere dalla notifica: dice "questo account, questo tavolo, fino a questa data", firmato dal server
const enc = new TextEncoder();
const b64u = (b: Uint8Array) => btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const unb64u = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4)), (c) => c.charCodeAt(0));
let keyP: Promise<CryptoKey> | null = null;
const hkey = () => keyP ??= crypto.subtle.importKey("raw", enc.encode("hpl-reply:" + Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
export async function replyToken(account: string, table: string, days = 4) {
  const p = b64u(enc.encode(JSON.stringify({ a: account, t: table, e: Date.now() + days * 864e5 })));
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", await hkey(), enc.encode(p)));
  return p + "." + b64u(sig);
}
export async function readToken(tk: string): Promise<{ a: string; t: string } | null> {
  try {
    const [p, s] = String(tk).split(".");
    if (!p || !s) return null;
    if (!(await crypto.subtle.verify("HMAC", await hkey(), unb64u(s), enc.encode(p)))) return null;
    const o = JSON.parse(new TextDecoder().decode(unb64u(p)));
    if (!o.a || !o.t || !(o.e > Date.now())) return null;
    return { a: o.a, t: o.t };
  } catch { return null; }
}

// chi ha silenziato la chat del tavolo non riceve le notifiche dei messaggi
// deno-lint-ignore no-explicit-any
export async function dropMuted(db: any, table_id: string, accounts: string[]) {
  if (!accounts.length) return accounts;
  const { data: mutes } = await db.from("hpl_chat_mute").select("acct, until").eq("table_id", table_id).in("acct", accounts);
  const now = Date.now();
  const muted = new Set((mutes ?? []).filter((m: { until: string | null }) => !m.until || Date.parse(m.until) > now).map((m: { acct: string }) => m.acct));
  return accounts.filter((a) => !muted.has(a));
}

type Msg = { title?: unknown; body?: unknown; url?: unknown; tag?: unknown; mid?: unknown };
// deno-lint-ignore no-explicit-any
export async function sendTo(db: any, table_id: string, accounts: string[], m: Msg) {
  if (!accounts.length) return { sent: 0, subs: 0, failed: [] };
  const { data: subs } = await db.from("hpl_push_subs").select("endpoint, p256dh, auth, account_id").in("account_id", accounts);
  const tag = typeof m.tag === "string" ? m.tag : undefined;
  const isChat = !!tag && tag.startsWith("chat-");
  const base = {
    title: String(m.title ?? "Home Poker").slice(0, 90),
    body: String(m.body ?? "").slice(0, 220),
    // solo collegamenti dentro l'app: una notifica non può portare a un sito esterno
    url: typeof m.url === "string" && /^\.\/(#[^\s]*)?$/.test(m.url) ? m.url : "./",
    tag,
  };
  const ru = Deno.env.get("SUPABASE_URL") + "/functions/v1/hpl-chat-reply";
  const toks = new Map<string, string>();
  let sent = 0;
  const failed: { host: string; code: number | null; msg: string }[] = [];
  await Promise.all((subs ?? []).map(async (s: { endpoint: string; p256dh: string; auth: string; account_id: string }) => {
    const host = (() => { try { return new URL(s.endpoint).host; } catch { return "?"; } })();
    try {
      let payload: Record<string, unknown> = base;
      if (isChat) {
        if (!toks.has(s.account_id)) toks.set(s.account_id, await replyToken(s.account_id, table_id));
        payload = { ...base, chat: { tk: toks.get(s.account_id), ru, mid: typeof m.mid === "number" ? m.mid : undefined } };
      }
      // "high": sui telefoni Android (Samsung soprattutto) senza priorità alta il risparmio batteria trattiene le notifiche
      await webpush.sendNotification({ endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } }, JSON.stringify(payload), { TTL: 86400, urgency: "high" });
      sent++;
    } catch (e) {
      const err = e as { statusCode?: number; body?: string; message?: string };
      const code = err.statusCode ?? null;
      console.error("push failed", host, code, String(err.body ?? err.message ?? e).slice(0, 300));
      failed.push({ host, code, msg: String(err.body ?? err.message ?? "").slice(0, 160) });
      if (code === 404 || code === 410) await db.from("hpl_push_subs").delete().eq("endpoint", s.endpoint);
    }
  }));
  return { sent, subs: (subs ?? []).length, failed };
}
