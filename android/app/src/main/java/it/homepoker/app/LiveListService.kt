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
            r.setTextViewText(R.id.name, p.optString("name"))
            val n = p.optInt("n")
            val sb = SpannableStringBuilder(PokerWidget.euro(p.optLong("in")))
            if (n > 1) sb.append(" · $n ingressi")
            val pend = p.optLong("pend")
            if (pend > 0) {
                val st = sb.length
                sb.append(" · ${PokerWidget.euro(pend)} da pagare")
                sb.setSpan(ForegroundColorSpan(c.getColor(R.color.lose)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            r.setTextViewText(R.id.info, sb)
            if (!host || buyIn <= 0) {
                r.setViewVisibility(R.id.btn, android.view.View.GONE)
            } else {
                r.setViewVisibility(R.id.btn, android.view.View.VISIBLE)
                when (pid) {
                    Store.busy(c) -> {
                        r.setTextViewText(R.id.btn, "Invio…")
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.btn_gold)
                        r.setTextColor(R.id.btn, 0xFF2A1E05.toInt())
                    }
                    Store.armed(c) -> {
                        r.setTextViewText(R.id.btn, "Confermi?")
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.btn_red)
                        r.setTextColor(R.id.btn, 0xFFFFFFFF.toInt())
                    }
                    else -> {
                        r.setTextViewText(R.id.btn, "+ " + PokerWidget.euroShort(buyIn))
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.btn_gold)
                        r.setTextColor(R.id.btn, 0xFF2A1E05.toInt())
                    }
                }
                r.setOnClickFillInIntent(R.id.btn, Intent().putExtra(PokerWidget.EXTRA_PID, pid))
            }
            return r
        }
    }
}
