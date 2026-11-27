package it.homepoker.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Memoria del widget: il gettone di collegamento e l'ultimo riassunto ricevuto. */
object Store {
    private const val P = "homepoker_widget"
    private val TOKEN_RE = Regex("^[0-9a-f]{48}$")

    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun validToken(t: String?) = t != null && TOKEN_RE.matches(t)
    fun token(c: Context): String? = prefs(c).getString("token", null)
    fun setToken(c: Context, t: String?) {
        prefs(c).edit().apply { if (t == null) clear() else { putString("token", t); remove("data"); remove("at") } }.apply()
    }

    fun data(c: Context): JSONObject? = prefs(c).getString("data", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun dataAt(c: Context): Long = prefs(c).getLong("at", 0L)
    fun failed(c: Context): Boolean = prefs(c).getBoolean("failed", false)
    fun loading(c: Context): Boolean = prefs(c).getBoolean("loading", false)
    fun setLoading(c: Context, v: Boolean) = prefs(c).edit().putBoolean("loading", v).apply()

    sealed class Result { object Ok : Result(); object Unlinked : Result(); object Offline : Result() }

    /** Scarica il riassunto dal server. Va chiamato fuori dal thread principale. */
    fun fetch(c: Context): Result {
        val t = token(c) ?: return Result.Unlinked
        return try {
            val con = (URL(BuildConfig.WIDGET_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("x-widget-token", t)
                setRequestProperty("Accept", "application/json")
            }
            try {
                when (con.responseCode) {
                    200 -> {
                        val body = con.inputStream.bufferedReader().use { it.readText() }
                        JSONObject(body) // controlla che sia JSON valido
                        prefs(c).edit().putString("data", body).putLong("at", System.currentTimeMillis()).putBoolean("failed", false).apply()
                        Result.Ok
                    }
                    401 -> { setToken(c, null); Result.Unlinked }
                    else -> { prefs(c).edit().putBoolean("failed", true).apply(); Result.Offline }
                }
            } finally { con.disconnect() }
        } catch (e: Exception) {
            prefs(c).edit().putBoolean("failed", true).apply()
            Result.Offline
        }
    }
}
