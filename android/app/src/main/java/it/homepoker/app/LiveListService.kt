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
        // i colori delle fiche: ogni giocatore ha sempre la sua
        val CHIPS = intArrayOf(0xFFC8323C.toInt(), 0xFF2F5DB8.toInt(), 0xFF1C1C22.toInt(), 0xFF7A3FB0.toInt(), 0xFFD2721E.toInt(), 0xFFB8336A.toInt())
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
            r.setTextViewText(R.id.initial, name.trim().take(1).uppercase())
            r.setInt(R.id.chipBase, "setColorFilter", CHIPS[Math.floorMod(name.lowercase().hashCode(), CHIPS.size)])
            val n = p.optInt("n")
            val sb = SpannableStringBuilder(PokerWidget.euroShort(p.optLong("in")) + " · $n buy-in")
            val pend = p.optLong("pend")
            if (pend > 0) {
                val st = sb.length
                sb.append(" · ${PokerWidget.euroShort(pend)} da pagare")
                sb.setSpan(ForegroundColorSpan(0xFFFF7A7F.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            r.setTextViewText(R.id.info, sb)
            if (!host || buyIn <= 0) {
                r.setViewVisibility(R.id.btn, android.view.View.GONE)
            } else {
                r.setViewVisibility(R.id.btn, android.view.View.VISIBLE)
                when (pid) {
                    Store.busy(c) -> {
                        r.setTextViewText(R.id.btn, "…")
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.w_chip_gold)
                        r.setTextColor(R.id.btn, 0xFF3A2A08.toInt())
                    }
                    Store.armed(c) -> {
                        r.setTextViewText(R.id.btn, "OK?")
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.w_chip_red)
                        r.setTextColor(R.id.btn, 0xFFFFFFFF.toInt())
                    }
                    else -> {
                        r.setTextViewText(R.id.btn, "+" + PokerWidget.euroShort(buyIn).removePrefix("€ "))
                        r.setInt(R.id.btn, "setBackgroundResource", R.drawable.w_chip_gold)
                        r.setTextColor(R.id.btn, 0xFF3A2A08.toInt())
                    }
                }
                r.setOnClickFillInIntent(R.id.btn, Intent().putExtra(PokerWidget.EXTRA_PID, pid))
            }
            return r
        }
    }
}
