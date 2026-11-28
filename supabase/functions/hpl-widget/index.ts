// Supabase Edge Function "hpl-widget": il riassunto per il widget Android.
// Non usa il login dell'app: il widget manda il suo gettone (creato dall'app con hpl_widget_token)
// e riceve solo i numeri da mostrare: serata in corso, prossima serata, saldo, chat.
import { createClient } from "npm:@supabase/supabase-js@2.58.0";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, x-widget-token",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};
const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });

type Doc = { path: string; data: Record<string, any> };
const sum = (a: number[]) => a.reduce((x, y) => x + (y || 0), 0);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    let body: Record<string, any> = {};
    if (req.method === "POST") { try { body = await req.json(); } catch { /* corpo vuoto */ } }
    const token = req.headers.get("x-widget-token") ?? new URL(req.url).searchParams.get("t") ?? String(body.token ?? "");
    if (!/^[0-9a-f]{48}$/.test(token)) return json({ error: "bad_token" }, 401);

    const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });

    // modifiche dal widget (re-buy, annulla): le regole sono nel database, come nell'app
    if (body.action) {
      const s = (v: unknown) => (typeof v === "string" && /^[A-Za-z0-9_.~-]{1,80}$/.test(v) ? v : null);
      if (!s(body.tid) || !s(body.gid)) return json({ error: "bad_request" }, 400);
      const { data, error } = await db.rpc("hpl_widget_act", {
        p_token: token, p_tid: body.tid, p_gid: body.gid, p_act: String(body.action), p_pid: s(body.pid), p_hid: s(body.hid),
      });
      if (error) throw error;
      if (data?.error === "bad_token") return json(data, 401);
      return json(data, data?.ok ? 200 : 409);
    }
    const { data: tk } = await db.from("hpl_widget_tokens").select("account_id").eq("token", token).maybeSingle();
    if (!tk) return json({ error: "bad_token" }, 401);
    const acct = tk.account_id as string;

    const [{ data: acc }, { data: links }] = await Promise.all([
      db.from("hpl_accounts").select("name").eq("id", acct).maybeSingle(),
      db.from("hpl_account_tables").select("table_id, player_id, last_at").eq("account_id", acct).order("last_at", { ascending: false }),
    ]);
    db.from("hpl_widget_tokens").update({ used_at: new Date().toISOString() }).eq("token", token).then(() => {});
    const L = links ?? [];
    if (!L.length) return json({ ok: true, name: acc?.name ?? "", table: null, live: null, next: null, owed: 0, owe: 0, chat: 0, at: new Date().toISOString() });

    const tids = L.map((l) => l.table_id);
    const main = L[0];
    const [{ data: tables }, { data: games }] = await Promise.all([
      db.from("hpl_tables").select("id, name").in("id", tids),
      db.from("hpl_docs").select("path, data").in("parent", tids.map((t) => `t/${t}/games`)),
    ]);
    const tname = (id: string) => (tables ?? []).find((t) => t.id === id)?.name ?? "";
    const gid = (p: string) => p.split("/")[3];
    const tid = (p: string) => p.split("/")[1];
    const G = (games ?? []) as Doc[];
    const lk = String(acc?.name ?? "").trim().toLowerCase();

    // saldo su tutti i tavoli: debiti ancora aperti delle serate chiuse
    let owed = 0, owe = 0;
    for (const g of G) {
      if (g.data?.status !== "closed") continue;
      const pid = L.find((l) => l.table_id === tid(g.path))?.player_id;
      for (const d of g.data.debts ?? []) {
        const rem = Number(d?.remainingAmount) || 0;
        if (rem <= 0) continue;
        const to = d.toPlayerId ? d.toPlayerId === pid : String(d.toPlayerName ?? "").toLowerCase() === lk;
        const from = d.fromPlayerId ? d.fromPlayerId === pid : String(d.fromPlayerName ?? "").toLowerCase() === lk;
        if (to) owed += rem;
        if (from) owe += rem;
      }
    }

    // serata in corso nel tavolo usato più di recente (altrimenti in un altro tavolo)
    const lives = G.filter((g) => g.data?.status === "live")
      .sort((a, b) => (tid(a.path) === main.table_id ? -1 : 0) - (tid(b.path) === main.table_id ? -1 : 0) || String(b.data.scheduledAt ?? "").localeCompare(String(a.data.scheduledAt ?? "")));
    let live = null;
    if (lives.length) {
      const g = lives[0];
      const P = Object.values(g.data.players ?? {}) as any[];
      const gross = sum(P.flatMap((p) => (p.buyIns ?? []).map((b: any) => Number(b.amountCents) || 0)));
      const me = L.find((l) => l.table_id === tid(g.path))?.player_id;
      const list = P.slice().sort((x, y) => (x.seq ?? 0) - (y.seq ?? 0)).map((p) => {
        const B = (p.buyIns ?? []) as any[];
        return {
          pid: p.playerId, name: p.playerName ?? "", n: B.length,
          in: sum(B.map((b) => Number(b.amountCents) || 0)),
          pend: sum(B.filter((b) => b.paymentStatus !== "PAID").map((b) => Number(b.amountCents) || 0)),
          out: p.cashOutCents != null || (p.cashOuts ?? []).length > 0,
        };
      });
      live = {
        table: tname(tid(g.path)), tid: tid(g.path), id: gid(g.path), pot: gross - (Number(g.data.dealerChips) || 0), players: P.length,
        since: g.data.scheduledAt ?? g.data.createdAt ?? null, kind: g.data.kind === "tournament" ? "torneo" : "cash",
        host: !!me && g.data.hostId === me, buyIn: Number(g.data.buyInCents) || 0, list,
      };
    }

    // prossima serata programmata
    const now = Date.now();
    const sch = G.filter((g) => g.data?.status === "scheduled" && Date.parse(g.data.scheduledAt) > now - 3 * 3600e3)
      .sort((a, b) => Date.parse(a.data.scheduledAt) - Date.parse(b.data.scheduledAt));
    let next = null;
    if (sch.length) {
      const g = sch[0];
      const { count } = await db.from("hpl_docs").select("path", { count: "exact", head: true }).eq("parent", `${g.path}/reservations`);
      next = { table: tname(tid(g.path)), tid: tid(g.path), at: g.data.scheduledAt, where: g.data.location ?? "", booked: count ?? 0, seats: g.data.maxSeats ?? null, id: gid(g.path) };
    }

    // messaggi non letti nella chat del tavolo principale
    let chat = 0;
    let msgs: { id: number; who: string; text: string }[] = [];
    const { data: rd } = await db.from("hpl_chat_read").select("last_id").eq("table_id", main.table_id).eq("acct", acct).maybeSingle();
    if (rd) {
      const { count: cc } = await db.from("hpl_chat").select("id", { count: "exact", head: true })
        .eq("table_id", main.table_id).eq("deleted", false).neq("author_acct", acct).gt("id", rd.last_id ?? 0);
      chat = Math.min(cc ?? 0, 99);
    }
    // gli ultimi messaggi degli altri (per le notifiche del programma Android)
    const { data: ms } = await db.from("hpl_chat").select("id, author_name, kind, body")
      .eq("table_id", main.table_id).eq("deleted", false).neq("author_acct", acct).gt("id", rd?.last_id ?? 0)
      .order("id", { ascending: false }).limit(5);
    const label: Record<string, string> = { photo: "📷 Foto", audio: "🎤 Messaggio vocale", poll: "📊 Sondaggio", sticker: "Adesivo", dice: "🎲 Dadi", card: "🃏 Carta", coin: "🪙 Moneta", event: "📅 Serata", result: "🏆 Risultati" };
    msgs = (ms ?? []).reverse().map((m: any) => ({
      id: m.id, who: m.author_name ?? "",
      text: m.kind === "text" ? String(m.body?.t ?? m.body?.text ?? "").slice(0, 160) : (label[m.kind] ?? "Nuovo messaggio"),
    }));

    return json({ ok: true, name: acc?.name ?? "", table: { id: main.table_id, name: tname(main.table_id) }, live, next, owed, owe, chat, msgs, at: new Date().toISOString() });
  } catch (e) {
    console.error("hpl-widget", e);
    return json({ error: "server" }, 500);
  }
});
