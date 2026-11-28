package it.homepoker.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF

import android.widget.RemoteViews
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class PokerWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(c, m, it) }
        if (Store.token(c) != null) { Refresh.schedule(c); Refresh.now(c) }
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id)

    override fun onDisabled(c: Context) = Refresh.cancel(c)

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_REFRESH -> { if (Store.token(c) != null) Refresh.now(c); renderAll(c) }
            ACTION_REBUY -> rebuy(c, i.getStringExtra(EXTRA_PID) ?: return)
            ACTION_UNDO -> undo(c)
            else -> super.onReceive(c, i)
        }
    }

    /** Primo tocco: chiede conferma. Secondo tocco entro pochi secondi: registra il re-buy. */
    private fun rebuy(c: Context, pid: String) {
        if (Store.busy(c) != null) return
        val lv = Store.data(c)?.optJSONObject("live") ?: return
        val pending = goAsync()
        if (Store.armed(c) != pid) {
            Store.arm(c, pid); renderAll(c)
            Handler(Looper.getMainLooper()).postDelayed({ renderAll(c); pending.finish() }, Store.ARM_MS + 300)
            return
        }
        Store.arm(c, null); Store.setBusy(c, pid); Store.setNote(c, null); renderAll(c)
        Thread {
            val r = Store.act(c, JSONObject().put("action", "rebuy").put("tid", lv.optString("tid")).put("gid", lv.optString("id")).put("pid", pid))
            if (r?.optBoolean("ok") == true) {
                val amt = r.optLong("amount")
                bump(c, pid, amt)
                Store.setLast(c, JSONObject().put("hid", r.optString("hid")).put("tid", lv.optString("tid")).put("gid", lv.optString("id"))
                    .put("text", "Re-buy a ${r.optString("name")} · ${euro(amt)}").put("at", System.currentTimeMillis()))
            } else Store.setNote(c, why(r), true)
            Store.setBusy(c, null)
            renderAll(c)
            Refresh.now(c)
            pending.finish()
        }.start()
    }

    private fun undo(c: Context) {
        val last = Store.last(c) ?: return
        if (Store.busy(c) != null) return
        val pending = goAsync()
        Store.setBusy(c, "undo"); renderAll(c)
        Thread {
            val r = Store.act(c, JSONObject().put("action", "undo").put("tid", last.optString("tid")).put("gid", last.optString("gid")).put("hid", last.optString("hid")))
            if (r?.optBoolean("ok") == true) { Store.setLast(c, null); Store.setNote(c, "Annullato: " + last.optString("text").replaceFirstChar { it.lowercase() }) }
            else { if (r?.optString("error") in setOf("already", "no_entry")) Store.setLast(c, null); Store.setNote(c, why(r), true) }
            Store.setBusy(c, null)
            renderAll(c)
            Refresh.now(c)
            pending.finish()
        }.start()
    }

    /** Aggiorna subito i numeri in memoria, senza aspettare il server. */
    private fun bump(c: Context, pid: String, amt: Long) {
        val d = Store.data(c) ?: return
        val lv = d.optJSONObject("live") ?: return
        lv.put("pot", lv.optLong("pot") + amt)
        val a = lv.optJSONArray("list") ?: return
        for (k in 0 until a.length()) {
            val p = a.optJSONObject(k) ?: continue
            if (p.optString("pid") == pid) { p.put("in", p.optLong("in") + amt); p.put("n", p.optInt("n") + 1); p.put("pend", p.optLong("pend") + amt) }
        }
        Store.saveData(c, d)
    }

    private fun why(r: JSONObject?) = when (r?.optString("error")) {
        null -> "Nessuna connessione: riprova"
        "not_host" -> "Solo chi tiene il banco può fare re-buy"
        "not_live" -> "La serata è già stata chiusa"
        "no_player" -> "Giocatore non più al tavolo"
        "changed" -> "Modificato nel frattempo: annulla dall'app"
        "already" -> "Era già stato annullato"
        "bad_token" -> "Widget scollegato: apri l'app"
        else -> "Non riuscito: riprova dall'app"
    }

    companion object {
        const val ACTION_REFRESH = "it.homepoker.app.REFRESH"
        const val ACTION_REBUY = "it.homepoker.app.REBUY"
        const val ACTION_UNDO = "it.homepoker.app.UNDO"
        const val EXTRA_PID = "pid"
        private val IT = Locale.ITALY

        fun renderAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, PokerWidget::class.java))
            ids.forEach { render(c, m, it) }
            if (ids.isNotEmpty()) m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        fun render(c: Context, m: AppWidgetManager, id: Int) {
            val v = Model(c)
            val large = if (v.live) buildLive(c, v, id) else build(c, R.layout.widget_large, v)
            val small = build(c, R.layout.widget_small, v)
            val rv = if (Build.VERSION.SDK_INT >= 31) {
                RemoteViews(mapOf(SizeF(100f, 40f) to small, SizeF(170f, 120f) to large))
            } else {
                val o = m.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
                if (w < 170 || h < 120) small else large
            }
            m.updateAppWidget(id, rv)
        }

        /** Cosa mostrare, calcolato una volta sola per tutte e due le misure. */
        private class Model(c: Context) {
            var label = ""; var big = ""; var sub = ""; var live = false
            var owed = 0L; var owe = 0L; var chat = 0; var money = false
            var updated = ""; var openUrl = BuildConfig.WEB_URL; var chatUrl: String? = null
            var balance = ""; var balanceColor = 0xE6FFFFFF.toInt(); var tableName = ""

            init {
                val d = Store.data(c)
                when {
                    Store.token(c) == null -> { label = "HOME POKER"; big = "COLLEGA IL WIDGET"; sub = "Apri l'app: si collega da solo" }
                    d == null -> { label = "HOME POKER"; big = "UN ATTIMO…"; sub = if (Store.failed(c)) "Nessuna connessione" else "Carico i dati del tavolo" }
                    else -> fill(c, d)
                }
                if (money) balance = when {
                    owe > 0 -> "Devi ${eurS(owe)}"
                    owed > 0 -> "Ti devono ${eurS(owed)}"
                    else -> "Conti in pari"
                }
            }

            private fun fill(c: Context, d: JSONObject) {
                money = true
                owed = d.optLong("owed"); owe = d.optLong("owe"); chat = d.optInt("chat")
                val main = d.optJSONObject("table")
                val mainName = main?.optString("name").orEmpty()
                val mainId = main?.optString("id").orEmpty()
                tableName = mainName
                val lv = d.optJSONObject("live")
                val nx = d.optJSONObject("next")
                when {
                    main == null -> { label = "HOME POKER"; big = "ENTRA IN UN TAVOLO"; sub = "Dall'app Home Poker"; money = false }
                    lv != null -> {
                        live = true
                        label = "IN CORSO · " + lv.optString("table").ifEmpty { mainName }.uppercase(IT)
                        big = eur(lv.optLong("pot"))
                        val n = lv.optInt("players")
                        sub = "Montepremi · $n al tavolo"
                        openUrl = deep("live", lv.optString("tid"), lv.optString("id"))
                    }
                    nx != null -> {
                        label = "PROSSIMA SERATA · " + nx.optString("table").ifEmpty { mainName }.uppercase(IT)
                        big = whenText(nx.optString("at")).uppercase(IT)
                        val seats = if (nx.isNull("seats")) 0 else nx.optInt("seats")
                        val b = nx.optInt("booked")
                        val parts = mutableListOf(if (seats > 0) "$b/$seats posti presi" else "$b ${if (b == 1) "prenotato" else "prenotati"}")
                        nx.optString("where").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                        sub = parts.joinToString(" · ")
                        openUrl = deep("book", nx.optString("tid"), nx.optString("id"))
                    }
                    else -> { label = mainName.uppercase(IT).ifEmpty { "HOME POKER" }; big = "NESSUNA SERATA"; sub = "Nessuna serata in programma" }
                }
                if (mainId.isNotEmpty()) chatUrl = deep("chat", mainId, "0")
                val hhmm = SimpleDateFormat("HH:mm", IT).format(Date(Store.dataAt(c)))
                updated = when {
                    Store.loading(c) -> "Aggiorno…"
                    Store.failed(c) -> "Offline · dati delle $hhmm"
                    else -> "Aggiornato alle $hhmm"
                }
            }
        }

        private fun build(c: Context, layout: Int, v: Model): RemoteViews {
            val r = RemoteViews(c.packageName, layout)
            val isLarge = layout == R.layout.widget_large
            val vis = { b: Boolean -> if (b) android.view.View.VISIBLE else android.view.View.GONE }
            r.setTextViewText(R.id.label, v.label)
            r.setTextViewText(R.id.big, v.big)
            r.setViewVisibility(R.id.chat, vis(v.chat > 0))
            r.setTextViewText(R.id.chat, if (layout == R.layout.widget_small) "${v.chat}" else "${v.chat} ${if (v.chat == 1) "nuovo" else "nuovi"}")
            r.setOnClickPendingIntent(R.id.root, open(c, v.openUrl, 1))
            v.chatUrl?.let { r.setOnClickPendingIntent(R.id.chat, open(c, it, 2)) }
            if (isLarge) {
                r.setTextViewText(R.id.sub, v.sub)
                r.setViewVisibility(R.id.money, vis(v.money))
                r.setTextViewText(R.id.owed, eurS(v.owed))
                r.setTextColor(R.id.owed, if (v.owed > 0) GREEN else IVORY)
                r.setTextViewText(R.id.owe, eurS(v.owe))
                r.setTextColor(R.id.owe, if (v.owe > 0) RED else IVORY)
                r.setTextViewText(R.id.updated, v.updated)
                r.setViewVisibility(R.id.refresh, vis(v.updated.isNotEmpty()))
                r.setOnClickPendingIntent(R.id.refresh, refresh(c))
                r.setOnClickPendingIntent(R.id.money, open(c, BuildConfig.WEB_URL, 3))
            } else {
                r.setViewVisibility(R.id.dot, vis(v.live))
                r.setTextViewText(R.id.sub, if (v.money) v.balance else v.sub)
            }
            return r
        }

        private val GREEN = 0xFF46DA80.toInt()
        private val RED = 0xFFFF5C50.toInt()
        private val IVORY = 0xFFF7F3EA.toInt()

        private fun buildLive(c: Context, v: Model, id: Int): RemoteViews {
            val r = RemoteViews(c.packageName, R.layout.widget_live)
            val lv = Store.data(c)?.optJSONObject("live")
            val host = lv?.optBoolean("host") == true
            val n = lv?.optInt("players") ?: 0
            r.setTextViewText(R.id.kicker, (lv?.optString("table")?.ifEmpty { null } ?: v.tableName).uppercase(IT) + " · $n AL TAVOLO")
            r.setTextViewText(R.id.label, if (lv?.optString("kind") == "torneo") "TORNEO LIVE" else "CASSA LIVE")
            r.setTextViewText(R.id.big, eur(lv?.optLong("pot") ?: 0L))
            var tot = 0L; var pend = 0L
            lv?.optJSONArray("list")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { tot += it.optLong("in"); pend += it.optLong("pend") } }
            r.setTextViewText(R.id.paid, "Incassato ${eurS(tot - pend)}")
            r.setTextViewText(R.id.pend, "Da pagare ${eurS(pend)}")
            r.setProgressBar(R.id.bar, 100, if (tot > 0) ((tot - pend) * 100 / tot).toInt() else 100, false)
            r.setViewVisibility(R.id.chat, if (v.chat > 0) android.view.View.VISIBLE else android.view.View.GONE)
            r.setTextViewText(R.id.chat, "${v.chat} ${if (v.chat == 1) "nuovo" else "nuovi"}")
            v.chatUrl?.let { r.setOnClickPendingIntent(R.id.chat, open(c, it, 2)) }
            r.setOnClickPendingIntent(R.id.head, open(c, v.openUrl, 1))
            r.setOnClickPendingIntent(R.id.potBox, open(c, v.openUrl, 4))

            val svc = Intent(c, LiveListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION") r.setRemoteAdapter(R.id.list, svc)
            r.setEmptyView(R.id.list, R.id.empty)
            val tpl = Intent(c, PokerWidget::class.java).setAction(ACTION_REBUY)
            r.setPendingIntentTemplate(R.id.list, PendingIntent.getBroadcast(c, 20, tpl,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)))

            val note = Store.note(c)
            val last = Store.last(c)
            val busy = Store.busy(c)
            var undo = false
            val (txt, col) = when {
                busy == "undo" -> "Annullo…" to R.color.muted
                note != null -> note.first to (if (note.second) R.color.lose else R.color.win)
                last != null -> { undo = true; "✓ " + last.optString("text") to R.color.win }
                Store.armed(c) != null -> "Tocca di nuovo per confermare" to R.color.gold
                !host -> "Il re-buy lo fa chi tiene il banco" to R.color.muted
                else -> v.updated to R.color.gold_dim
            }
            r.setTextViewText(R.id.status, txt)
            r.setTextColor(R.id.status, when (col) { R.color.win -> GREEN; R.color.lose -> RED; R.color.gold -> 0xFFFFD27A.toInt(); else -> 0xFF7C8A82.toInt() })
            r.setViewVisibility(R.id.undo, if (undo) android.view.View.VISIBLE else android.view.View.GONE)
            r.setOnClickPendingIntent(R.id.undo, PendingIntent.getBroadcast(c, 21, Intent(c, PokerWidget::class.java).setAction(ACTION_UNDO),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            r.setOnClickPendingIntent(R.id.refresh, refresh(c))
            return r
        }

        private val NF = NumberFormat.getNumberInstance(IT).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        /** come nell'app: 140,00 € */
        fun eur(cents: Long): String = NF.format(cents / 100.0) + "\u00A0€"
        /** come nell'app: 80 € (i decimali solo se servono) */
        fun eurS(cents: Long): String = (if (cents % 100 == 0L) NumberFormat.getIntegerInstance(IT).format(cents / 100) else NF.format(cents / 100.0)) + "\u00A0€"

        fun euroShort(cents: Long): String =
            if (cents % 100 == 0L) "€ ${cents / 100}" else euro(cents)

        private fun deep(view: String, tid: String, gid: String) =
            if (tid.isEmpty()) BuildConfig.WEB_URL
            else "${BuildConfig.WEB_URL}#go=$view&t=${Uri.encode(tid)}&g=${Uri.encode(gid.ifEmpty { "0" })}"

        private fun open(c: Context, url: String, code: Int): PendingIntent {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url), c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun refresh(c: Context): PendingIntent {
            val i = Intent(c, PokerWidget::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(c, 10, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun euro(cents: Long): String {
            val f = NumberFormat.getNumberInstance(IT).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
            return "€ " + f.format(cents / 100.0)
        }

        private fun whenText(iso: String): String {
            val t = runCatching { parseIso(iso) }.getOrNull() ?: return "In programma"
            val cal = Calendar.getInstance(); val day = Calendar.getInstance().apply { time = t }
            val hhmm = SimpleDateFormat("HH:mm", IT).format(t)
            fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (same(cal, day)) return "Stasera · $hhmm"
            cal.add(Calendar.DAY_OF_YEAR, 1)
            if (same(cal, day)) return "Domani · $hhmm"
            val s = SimpleDateFormat("EEE d MMM", IT).format(t)
            return s.replaceFirstChar { it.titlecase(IT) } + " · " + hhmm
        }

        private fun parseIso(s: String): Date {
            // es. 2026-11-30T20:00:00.000Z oppure 2026-11-30T21:00:00+01:00
            val clean = s.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00").replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(clean)!!
        }
    }
}
