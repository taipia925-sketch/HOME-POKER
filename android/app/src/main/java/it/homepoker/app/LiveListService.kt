package it.homepoker.app

import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject

/** La lista dei giocatori della serata in corso, dentro il widget. */
class LiveListService : RemoteViewsService() {
    companion object {
        // le fiche-avatar dell'app, una ogni 30° di colore
        val AVA = intArrayOf(R.drawable.w2_ava_0, R.drawable.w2_ava_1, R.drawable.w2_ava_2, R.drawable.w2_ava_3, R.drawable.w2_ava_4, R.drawable.w2_ava_5,
            R.drawable.w2_ava_6, R.drawable.w2_ava_7, R.drawable.w2_ava_8, R.drawable.w2_ava_9, R.drawable.w2_ava_10, R.drawable.w2_ava_11)
        /** lo stesso colore che l'app dà a quel nome (hueOf) */
        fun hueBucket(n: String): Int {
            var h = 7
            for (ch in n.lowercase()) h = (h * 31 + ch.code) % 360
            return Math.round(h / 30f) % 12
        }
        fun inits(n: String): String {
            val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return ((w.getOrNull(0)?.take(1) ?: "?") + (if (w.size > 1) w[1].take(1) else "")).uppercase()
        }
    }
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val c: Context) : RemoteViewsFactory {
        private var rows: List<JSONObject> = emptyList()
        private var host = false
        private var buyIn = 0L

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun onDataSetChanged() {
            val lv = Store.data(c)?.optJSONObject("live")
            host = lv?.optBoolean("host") == true
            buyIn = lv?.optLong("buyIn") ?: 0L
            val a = lv?.optJSONArray("list")
            rows = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        override fun getCount() = rows.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = rows.getOrNull(i)?.optString("pid")?.hashCode()?.toLong() ?: i.toLong()
        override fun hasStableIds() = true

        override fun getViewAt(i: Int): RemoteViews {
            val r = RemoteViews(c.packageName, R.layout.widget_row)
            val p = rows.getOrNull(i) ?: return r
            val pid = p.optString("pid")
            val name = p.optString("name")
            r.setTextViewText(R.id.name, name)
            r.setTextViewText(R.id.initial, inits(name))
            r.setImageViewResource(R.id.ava, AVA[hueBucket(name)])
            val n = p.optInt("n")
            r.setTextViewText(R.id.info, "$n ${if (n == 1) "ingresso" else "ingressi"} · ${PokerWidget.eurS(p.optLong("in"))}")
            val pend = p.optLong("pend")
            r.setViewVisibility(R.id.due, if (pend > 0) android.view.View.VISIBLE else android.view.View.GONE)
            r.setTextViewText(R.id.due, "${PokerWidget.eurS(pend)} da pagare")
            if (!host || buyIn <= 0) {
                r.setViewVisibility(R.id.btn, android.view.View.GONE)
            } else {
                r.setViewVisibility(R.id.btn, android.view.View.VISIBLE)
                when (pid) {
                    Store.busy(c) -> {
                        r.setTextViewText(R.id.btn, "Invio…")
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.w2_rebuy)
                        r.setTextColor(R.id.btn, 0xFF7CF5B0.toInt())
                    }
                    Store.armed(c) -> {
                        r.setTextViewText(R.id.btn, "Conferma")
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.w2_confirm)
                        r.setTextColor(R.id.btn, 0xFFFFFFFF.toInt())
                    }
                    else -> {
                        r.setTextViewText(R.id.btn, "+ Re-buy")
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.w2_rebuy)
                        r.setTextColor(R.id.btn, 0xFF7CF5B0.toInt())
                    }
                }
                r.setOnClickFillInIntent(R.id.btn, Intent().putExtra(PokerWidget.EXTRA_PID, pid))
            }
            return r
        }
    }
}
