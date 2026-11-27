package it.homepoker.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Riceve homepoker://widget?t=GETTONE dalla web app e collega il widget al tuo account. */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri?.getQueryParameter("off") == "1") {
            Store.setToken(this, null)
            Refresh.cancel(this)
            PokerWidget.renderAll(this)
            Toast.makeText(this, "Widget scollegato", Toast.LENGTH_SHORT).show()
        } else {
            val t = uri?.getQueryParameter("t")
            if (Store.validToken(t)) {
                val first = Store.token(this) != t
                Store.setToken(this, t)
                Refresh.schedule(this)
                Refresh.now(this)
                PokerWidget.renderAll(this)
                if (uri?.getQueryParameter("quiet") != "1" || first)
                    Toast.makeText(this, "Widget collegato ✓", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
