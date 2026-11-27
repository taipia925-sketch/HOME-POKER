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
        if (i.action == ACTION_REFRESH) {
            if (Store.token(c) != null) Refresh.now(c)
            renderAll(c)
        } else super.onReceive(c, i)
    }

    companion object {
        const val ACTION_REFRESH = "it.homepoker.app.REFRESH"
        private val IT = Locale.ITALY

        fun renderAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, PokerWidget::class.java)).forEach { render(c, m, it) }
        }

        fun render(c: Context, m: AppWidgetManager, id: Int) {
            val v = Model(c)
            val large = build(c, R.layout.widget_large, v)
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
            var balance = ""; var balanceColor = 0; var tableName = ""

            init {
                val d = Store.data(c)
                when {
                    Store.token(c) == null -> {
                        label = "COLLEGA IL WIDGET"; big = "Apri Home Poker"
                        sub = "si collega da solo al tuo account"
                    }
                    d == null -> {
                        label = "HOME POKER"; big = "Carico…"; sub = if (Store.failed(c)) "nessuna connessione" else "un attimo"
                    }
                    else -> fill(c, d)
                }
                balanceColor = c.getColor(R.color.muted)
                if (money) {
                    if (owe > 0) { balance = "Devi ${euro(owe)}"; balanceColor = c.getColor(R.color.lose) }
                    else if (owed > 0) { balance = "Ti devono ${euro(owed)}"; balanceColor = c.getColor(R.color.win) }
                    else { balance = "Conti in pari"; balanceColor = c.getColor(R.color.muted) }
                }
            }

            private fun fill(c: Context, d: JSONObject) {
                money = true
                owed = d.optLong("owed"); owe = d.optLong("owe"); chat = d.optInt("chat")
                val main = d.optJSONObject("table")
                val mainName = main?.optString("name").orEmpty()
                val mainId = main?.optString("id").orEmpty()
                val lv = d.optJSONObject("live")
                val nx = d.optJSONObject("next")
                when {
                    main == null -> { label = "NESSUN TAVOLO"; big = "Entra in un tavolo"; sub = "dall'app Home Poker"; money = false }
                    lv != null -> {
                        live = true
                        val other = lv.optString("table").takeIf { it.isNotEmpty() && it != mainName }
                        label = "SERATA IN CORSO" + (other?.let { " · $it" } ?: "")
                        big = euro(lv.optLong("pot"))
                        val n = lv.optInt("players")
                        sub = "montepremi · $n ${if (n == 1) "giocatore" else "giocatori"} · ${lv.optString("kind", "cash")}"
                        openUrl = deep("detail", lv.optString("tid"), lv.optString("id"))
                    }
                    nx != null -> {
                        val other = nx.optString("table").takeIf { it.isNotEmpty() && it != mainName }
                        label = "PROSSIMA SERATA" + (other?.let { " · $it" } ?: "")
                        big = whenText(nx.optString("at"))
                        val seats = if (nx.isNull("seats")) 0 else nx.optInt("seats")
                        val b = nx.optInt("booked")
                        val parts = mutableListOf(if (seats > 0) "$b/$seats posti presi" else "$b ${if (b == 1) "prenotato" else "prenotati"}")
                        nx.optString("where").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                        sub = parts.joinToString(" · ")
                        openUrl = deep("book", nx.optString("tid"), nx.optString("id"))
                    }
                    else -> { label = "NESSUNA SERATA"; big = mainName.ifEmpty { "Tutto tranquillo" }; sub = "nessuna serata in programma" }
                }
                if (mainId.isNotEmpty()) chatUrl = deep("chat", mainId, "0")
                val at = Store.dataAt(c)
                val hhmm = SimpleDateFormat("HH:mm", IT).format(Date(at))
                updated = when {
                    Store.loading(c) -> "aggiorno…"
                    Store.failed(c) -> "offline · dati delle $hhmm"
                    else -> "aggiornato alle $hhmm"
                }
                tableName = mainName
            }
        }

        private fun build(c: Context, layout: Int, v: Model): RemoteViews {
            val r = RemoteViews(c.packageName, layout)
            val isLarge = layout == R.layout.widget_large
            r.setTextViewText(R.id.label, if (isLarge) v.label else (if (v.live) "IN CORSO" else v.label.substringBefore(" · ")))
            r.setTextViewText(R.id.big, v.big)
            r.setViewVisibility(R.id.dot, if (v.live) android.view.View.VISIBLE else android.view.View.GONE)
            r.setViewVisibility(R.id.chat, if (v.chat > 0) android.view.View.VISIBLE else android.view.View.GONE)
            r.setTextViewText(R.id.chat, if (isLarge) "💬 ${v.chat}" else "${v.chat}")
            r.setOnClickPendingIntent(R.id.root, open(c, v.openUrl, 1))
            v.chatUrl?.let { r.setOnClickPendingIntent(R.id.chat, open(c, it, 2)) }
            if (isLarge) {
                r.setTextViewText(R.id.table, if (v.tableName.isNotEmpty()) "· ${v.tableName}" else "")
                r.setTextViewText(R.id.sub, v.sub)
                r.setViewVisibility(R.id.money, if (v.money) android.view.View.VISIBLE else android.view.View.GONE)
                r.setTextViewText(R.id.owed, euro(v.owed))
                r.setTextColor(R.id.owed, c.getColor(if (v.owed > 0) R.color.win else R.color.cream))
                r.setTextViewText(R.id.owe, euro(v.owe))
                r.setTextColor(R.id.owe, c.getColor(if (v.owe > 0) R.color.lose else R.color.cream))
                r.setTextViewText(R.id.updated, v.updated)
                r.setViewVisibility(R.id.refresh, if (v.updated.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
                r.setOnClickPendingIntent(R.id.refresh, refresh(c))
                r.setOnClickPendingIntent(R.id.money, open(c, BuildConfig.WEB_URL, 3))
            } else {
                if (v.money) {
                    r.setTextViewText(R.id.sub, v.balance); r.setTextColor(R.id.sub, v.balanceColor)
                } else {
                    r.setTextViewText(R.id.sub, v.sub); r.setTextColor(R.id.sub, c.getColor(R.color.muted))
                }
            }
            return r
        }

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
