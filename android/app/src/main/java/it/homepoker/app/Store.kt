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

    // stato delle azioni dal widget
    fun armed(c: Context): String? = prefs(c).getString("armed", null)?.takeIf { System.currentTimeMillis() - prefs(c).getLong("armedAt", 0) < ARM_MS }
    fun arm(c: Context, pid: String?) = prefs(c).edit().putString("armed", pid).putLong("armedAt", System.currentTimeMillis()).apply()
    fun busy(c: Context): String? = prefs(c).getString("busy", null)?.takeIf { System.currentTimeMillis() - prefs(c).getLong("busyAt", 0) < 20000 }
    fun setBusy(c: Context, pid: String?) = prefs(c).edit().putString("busy", pid).putLong("busyAt", System.currentTimeMillis()).apply()
    /** ultima modifica fatta dal widget, annullabile per 10 minuti */
    fun last(c: Context): JSONObject? = prefs(c).getString("last", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { System.currentTimeMillis() - it.optLong("at") < 10 * 60000 }
    fun setLast(c: Context, o: JSONObject?) = prefs(c).edit().putString("last", o?.toString()).apply()
    /** messaggio breve nel piede del widget (errore o conferma) */
    fun note(c: Context): Pair<String, Boolean>? = prefs(c).getString("note", null)
        ?.takeIf { System.currentTimeMillis() - prefs(c).getLong("noteAt", 0) < 60000 }?.let { it to prefs(c).getBoolean("noteErr", false) }
    fun setNote(c: Context, t: String?, err: Boolean = false) =
        prefs(c).edit().putString("note", t).putLong("noteAt", System.currentTimeMillis()).putBoolean("noteErr", err).apply()
    fun saveData(c: Context, o: JSONObject) = prefs(c).edit().putString("data", o.toString()).apply()
    const val ARM_MS = 6000L
    /** il widget mostra il tavolo aperto (true) o il saldo (false) */
    fun tableMode(c: Context, id: Int) = prefs(c).getBoolean("table_$id", false)
    fun setTableMode(c: Context, id: Int, on: Boolean) = prefs(c).edit().putBoolean("table_$id", on).apply()

    /** Manda una modifica (re-buy, annulla). null = nessuna connessione. */
    fun act(c: Context, body: JSONObject): JSONObject? {
        val t = token(c) ?: return JSONObject().put("error", "bad_token")
        return try {
            val con = (URL(BuildConfig.WIDGET_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "POST"; doOutput = true
                setRequestProperty("x-widget-token", t)
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                con.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = con.responseCode
                val txt = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
                if (code == 401) setToken(c, null)
                runCatching { JSONObject(txt) }.getOrElse { JSONObject().put("error", "server") }
            } finally { con.disconnect() }
        } catch (e: Exception) { null }
    }

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
