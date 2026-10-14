// @ts-nocheck
// Supabase Edge Function "hpl-news": raccoglie le notizie di poker da alcuni feed RSS
// (tornei mondiali, risultati, curiosità e gossip), solo in italiano, e le restituisce già pulite all'app.
// I feed si leggono qui perché dal browser sarebbero bloccati (CORS).

const FEEDS = [
  { url: "https://news.google.com/rss/search?q=poker+(WSOP+OR+EPT+OR+WPT+OR+torneo+OR+%22Main+Event%22)&hl=it&gl=IT&ceid=IT:it", lang: "it" },
  { url: "https://news.google.com/rss/search?q=poker+(gossip+OR+scandalo+OR+vip+OR+celebrit%C3%A0+OR+polemica)&hl=it&gl=IT&ceid=IT:it", lang: "it", tag: "gossip" },
  { url: "https://news.google.com/rss/search?q=poker+live+OR+%22poker+online%22+OR+pokerstars&hl=it&gl=IT&ceid=IT:it", lang: "it" },
  { url: "https://news.google.com/rss/search?q=%22poker%22+when:7d&hl=it&gl=IT&ceid=IT:it", lang: "it" },
  { url: "https://it.pokernews.com/rss.php", lang: "it", src: "PokerNews" },
  { url: "https://www.assopoker.com/feed/", lang: "it", src: "Assopoker" },
];
const TTL = 20 * 60 * 1000;
let cache = null;
let stats = [];

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};
const json = (o) => new Response(JSON.stringify(o), { headers: { ...cors, "Content-Type": "application/json" } });

const ENT = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", egrave: "è", eacute: "é", agrave: "à", ograve: "ò", ugrave: "ù", igrave: "ì", rsquo: "’", lsquo: "‘", ldquo: "“", rdquo: "”", hellip: "…", ndash: "–", mdash: "—" };
function decode(s) {
  return String(s || "")
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d))
    .replace(/&([a-z]+);/gi, (m, n) => ENT[n.toLowerCase()] ?? m);
}
const strip = (s) => decode(decode(s).replace(/<[^>]*>/g, " ")).replace(/\s+/g, " ").trim();
const tagOf = (x, t) => { const m = x.match(new RegExp(`<${t}(?:\\s[^>]*)?>([\\s\\S]*?)</${t}>`, "i")); return m ? m[1] : ""; };
const attrOf = (x, t, a) => { const m = x.match(new RegExp(`<${t}\\b[^>]*\\b${a}=["']([^"']+)["']`, "i")); return m ? decode(m[1]) : ""; };

function parse(xml, feed) {
  const out = [];
  const items = xml.match(/<item\b[\s\S]*?<\/item>/gi) || xml.match(/<entry\b[\s\S]*?<\/entry>/gi) || [];
  for (const it of items.slice(0, 40)) {
    let title = strip(tagOf(it, "title"));
    let link = strip(tagOf(it, "link")) || attrOf(it, "link", "href");
    const when = Date.parse(strip(tagOf(it, "pubDate") || tagOf(it, "published") || tagOf(it, "updated") || tagOf(it, "dc:date"))) || 0;
    let src = feed.src || strip(tagOf(it, "source"));
    // Google News: "Titolo - Testata"
    if (!feed.src && src && title.endsWith(" - " + src)) title = title.slice(0, -(src.length + 3));
    const rawDesc = tagOf(it, "description") || tagOf(it, "summary") || tagOf(it, "content:encoded");
    let img = attrOf(it, "media:content", "url") || attrOf(it, "media:thumbnail", "url") || attrOf(it, "enclosure", "url") || attrOf(decode(rawDesc), "img", "src");
    if (img && !/^https:\/\//.test(img)) img = "";
    let desc = feed.src ? strip(rawDesc).slice(0, 220) : "";
    if (desc && desc.length === 220) desc = desc.replace(/\s\S*$/, "") + "…";
    if (!title || !/^https?:\/\//.test(link)) continue;
    out.push({ title: title.slice(0, 200), link, when, src: src || "", img, desc, lang: feed.lang, tag: feed.tag || classify(title + " " + desc) });
  }
  return out;
}
/* solo poker: fuori pronostici di calcio, scommesse sportive, slot e lotterie */
const POKER = /poker|wsop|\bept\b|\bwpt\b|world series|hold.?em|omaha|bracelet|braccialett|main event|high roller|sunday million|sit\s?(&|and|n)\s?go|spin\s?(&|and)\s?go|\bmtt\b|cash game|final table|tavolo finale|\bitm\b|day ?[123][a-d]?\b|chipleader|chip leader|bluff|showdown|river|flop/i;
const NOT_POKER = /pronostic|quote|scommess|calcio|serie a\b|champions|europa league|conference league|campionato|partita di|gol\b|slot|lotto|superenalotto|gratta|roulette|blackjack|ippica|tennis|basket|formula 1|motogp/i;
const isPoker = (t, src) => {
  if (NOT_POKER.test(t) && !/poker/i.test(t)) return false;
  return POKER.test(t) || /poker/i.test(src || ""); // le testate di poker valgono anche senza la parola nel titolo
};
function classify(t) {
  const s = t.toLowerCase();
  if (/gossip|scandal|cheat|truff|barare|polemic|drama|vip|celebr|attor|cantant|calciat|influencer|streamer|lite|accus|arrest|denunc|squalific|ban\b|bannat/.test(s)) return "gossip";
  if (/wsop|ept|wpt|world series|main event|bracelet|braccialett|torneo|tournament|high roller|final table|tavolo finale|vince|wins|champion|campion|festival|series/.test(s)) return "tornei";
  return "news";
}
const norm = (t) => t.toLowerCase().replace(/[^a-z0-9à-ù]+/g, " ").trim().slice(0, 60);

async function grab(feed) {
  try {
    const ac = new AbortController();
    const to = setTimeout(() => ac.abort(), 6000);
    const r = await fetch(feed.url, { signal: ac.signal, headers: { "User-Agent": "Mozilla/5.0 (HomePoker news)", Accept: "application/rss+xml, application/xml, text/xml, */*" } });
    clearTimeout(to);
    if (!r.ok) return [];
    return parse(await r.text(), feed);
  } catch (_) { return []; }
}

async function collect() {
  const lists = await Promise.all(FEEDS.map(grab));
  stats = FEEDS.map((f, i) => ({ src: f.src || "Google News", n: lists[i].length }));
  const seen = new Set(), all = [];
  const weekAgo = Date.now() - 21 * 864e5;
  for (const it of lists.flat().sort((a, b) => b.when - a.when)) {
    const k = norm(it.title);
    if (seen.has(k) || seen.has(it.link)) continue;
    if (!isPoker(it.title + " " + it.desc, it.src)) continue;
    if (it.when && it.when < weekAgo) continue;
    seen.add(k); seen.add(it.link);
    all.push(it);
  }
  return all.slice(0, 60);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const now = Date.now();
    if (!cache || now - cache.at > TTL || !cache.items.length) {
      const items = await collect();
      if (items.length || !cache) cache = { at: now, items };
    }
    return json({ ok: true, at: cache.at, items: cache.items, feeds: stats });
  } catch (e) {
    return json({ error: String(e?.message || e) });
  }
});

// per le prove in locale
export { parse, classify };
