package it.homepoker.app

import android.os.Bundle
import com.google.androidbrowserhelper.trusted.LauncherActivity

/** Apre Home Poker a schermo intero con Chrome: stesso sito, stesso account. */
class MainActivity : LauncherActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.token(this) != null) Refresh.now(this)
    }
}
