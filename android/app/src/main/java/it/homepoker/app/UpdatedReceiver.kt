package it.homepoker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Dopo un aggiornamento (o un riavvio) ridisegna il widget e riprende gli aggiornamenti. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        PokerWidget.renderAll(c)
        if (Store.token(c) != null) { Refresh.schedule(c); Refresh.now(c) }
    }
}
