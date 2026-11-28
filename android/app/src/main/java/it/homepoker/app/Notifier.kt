package it.homepoker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Le notifiche del programma: le calcola confrontando il riassunto nuovo con quello di prima. */
object Notifier {
    private const val CH = "homepoker"
    private const val ID_CHAT = 1
    private const val ID_LIVE = 2
    private const val ID_NEXT = 3
    private const val ID_MONEY = 4

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Home Poker", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Chat, serate e pagamenti del tuo tavolo"
            })
    }

    fun clearChat(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID_CHAT)

    private fun prefs(c: Context) = c.getSharedPreferences("homepoker_notif", Context.MODE_PRIVATE)

    fun check(c: Context, old: JSONObject?, now: JSONObject) {
        channel(c)
        val p = prefs(c)
        val tname = now.optJSONObject("table")?.optString("name").orEmpty()
        val tid = now.optJSONObject("table")?.optString("id").orEmpty()

        // chat: i messaggi nuovi dall'ultima volta (mai quelli già visti)
        val msgs = now.optJSONArray("msgs")
        val seen = p.getLong("lastMsg", -1L)
        var maxId = seen
        val fresh = mutableListOf<Pair<String, String>>()
        if (msgs != null) for (k in 0 until msgs.length()) {
            val m = msgs.optJSONObject(k) ?: continue
            val id = m.optLong("id")
            if (id > maxId) maxId = id
            if (seen >= 0 && id > seen) fresh.add(m.optString("who") to m.optString("text"))
        }
        if (maxId != seen) p.edit().putLong("lastMsg", maxId).apply()
        if (fresh.isNotEmpty() && !MainActivity.visible) {
            val n = now.optInt("chat").coerceAtLeast(fresh.size)
            val style = Notification.InboxStyle().setSummaryText(tname)
            fresh.takeLast(5).forEach { (w, t) -> style.addLine(if (w.isNotEmpty()) "$w: $t" else t) }
            val last = fresh.last()
            show(c, ID_CHAT, if (n == 1) "${last.first.ifEmpty { "Nuovo messaggio" }} · $tname" else "$n messaggi nuovi · $tname",
                if (last.first.isNotEmpty()) "${last.first}: ${last.second}" else last.second,
                deep("chat", tid, "0"), style)
        }

        if (old == null) return // prima lettura: si memorizza e basta

        val lv = now.optJSONObject("live"); val olv = old.optJSONObject("live")
        if (lv != null && lv.optString("id") != olv?.optString("id")) {
            val n = lv.optInt("players")
            show(c, ID_LIVE, "Serata iniziata · ${lv.optString("table")}", "Montepremi ${PokerWidget.euro(lv.optLong("pot"))} · $n ${if (n == 1) "giocatore" else "giocatori"}",
                deep("detail", lv.optString("tid"), lv.optString("id")))
        }
        val nx = now.optJSONObject("next"); val onx = old.optJSONObject("next")
        if (nx != null && nx.optString("id") != onx?.optString("id")) {
            show(c, ID_NEXT, "Nuova serata in programma · ${nx.optString("table")}", "Tocca per prenotare il tuo posto",
                deep("book", nx.optString("tid"), nx.optString("id")))
        }
        val owe = now.optLong("owe"); val owed = now.optLong("owed")
        if (owe > old.optLong("owe")) show(c, ID_MONEY, "Conto da saldare", "Devi ${PokerWidget.euro(owe)} in tutto", BuildConfig.WEB_URL)
        else if (owed > old.optLong("owed")) show(c, ID_MONEY, "Ti devono dei soldi", "Ti devono ${PokerWidget.euro(owed)} in tutto", BuildConfig.WEB_URL)
    }

    private fun deep(v: String, tid: String, gid: String) =
        if (tid.isEmpty()) BuildConfig.WEB_URL else "${BuildConfig.WEB_URL}#go=$v&t=${Uri.encode(tid)}&g=${Uri.encode(gid.ifEmpty { "0" })}"

    private fun show(c: Context, id: Int, title: String, text: String, url: String, style: Notification.Style? = null) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url), c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(c, 100 + id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat).setColor(c.getColor(R.color.gold))
            .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true)
        if (style != null) b.setStyle(style) else b.setStyle(Notification.BigTextStyle().bigText(text))
        nm.notify(id, b.build())
    }
}
