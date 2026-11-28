package it.homepoker.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File

/** Home Poker come programma: il sito gira dentro l'app, senza Chrome. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var micReq: PermissionRequest? = null

    companion object {
        @Volatile var visible = false
        private const val REQ_FILE = 11
        private const val REQ_MIC = 12
        private const val REQ_NOTIF = 13
        private val HOST = Uri.parse(BuildConfig.WEB_URL).host
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.felt_dark)
        window.navigationBarColor = getColor(R.color.felt_dark)

        val box = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.felt_dark)) }
        web = WebView(this).apply { setBackgroundColor(Color.parseColor("#050807")) }
        box.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(box)
        // barra di stato, barra di navigazione e tastiera: il sito resta sempre nello spazio libero
        box.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = ins.getInsets(WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }

        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString HomePokerApp/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
            WebSettingsCompat.setWebAuthenticationSupport(web.settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
        web.addJavascriptInterface(Bridge(), "HPAndroid")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, _, cd, mime, _ -> download(url, cd, mime) }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(startUrl(intent))

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        Notifier.channel(this)
        Refresh.schedule(this)
        if (Store.token(this) != null) Refresh.now(this)
    }

    private fun startUrl(i: Intent?): String {
        val d = i?.data
        return if (d != null && d.scheme == "https" && d.host == HOST) d.toString() else BuildConfig.WEB_URL
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val d = intent.data ?: return
        if (d.scheme != "https" || d.host != HOST) return
        val hash = d.fragment
        // la pagina è già aperta: basta cambiare il pezzo dopo # e l'app si sposta da sola
        if (hash != null && web.url?.startsWith(BuildConfig.WEB_URL) == true)
            web.evaluateJavascript("location.hash=${org.json.JSONObject.quote("#$hash")}", null)
        else web.loadUrl(d.toString())
    }

    override fun onResume() { super.onResume(); visible = true; web.onResume(); Notifier.clearChat(this) }
    override fun onPause() { visible = false; web.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val u = r.url
            if (u.scheme == "https" && u.host == HOST && (u.path ?: "/").startsWith("/HOME-POKER")) return false
            if (u.scheme == "homepoker") { linkFromUri(u); return true }
            if (u.scheme == "intent") {
                val it = runCatching { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return true
                if (it.scheme == "homepoker" && it.data != null) { linkFromUri(it.data!!); return true }
                if (it.`package` == packageName) return true
                it.addCategory(Intent.CATEGORY_BROWSABLE); it.component = null; it.selector = null
                try { startActivity(it) } catch (e: ActivityNotFoundException) {
                    it.getStringExtra("browser_fallback_url")?.let { f -> open(Uri.parse(f)) }
                }
                return true
            }
            open(u); return true
        }

        override fun onPageFinished(v: WebView, url: String) {
            // i file creati dall'app (Excel, copie di sicurezza) si salvano nei Download del telefono
            v.evaluateJavascript("""(function(){if(window.__hpdl)return;window.__hpdl=1;
              document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[download]');
                if(!a||!/^(blob|data):/.test(a.href))return;e.preventDefault();
                fetch(a.href).then(function(r){return r.blob()}).then(function(b){var f=new FileReader();
                  f.onload=function(){HPAndroid.save(a.getAttribute('download')||'home-poker',f.result)};f.readAsDataURL(b)});},true);})();""", null)
        }
    }

    private fun open(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: Exception) {}
    }

    private fun linkFromUri(u: Uri) {
        val t = u.getQueryParameter("t")
        if (Store.validToken(t)) link(t!!, u.getQueryParameter("quiet") == "1")
    }

    private fun link(t: String, quiet: Boolean) {
        val first = Store.token(this) != t
        Store.setToken(this, t)
        Refresh.schedule(this); Refresh.now(this); PokerWidget.renderAll(this)
        if (first || !quiet) Toast.makeText(this, "Widget collegato al tuo account ✓", Toast.LENGTH_SHORT).show()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null); fileCb = cb
            return try { startActivityForResult(p.createIntent().addCategory(Intent.CATEGORY_OPENABLE), REQ_FILE); true }
            catch (e: Exception) { fileCb = null; false }
        }

        override fun onPermissionRequest(r: PermissionRequest) {
            runOnUiThread {
                val wantsMic = r.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (wantsMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    micReq = r; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                } else r.grant(r.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray())
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == REQ_FILE) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null; return }
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        if (req == REQ_MIC) {
            val r = micReq; micReq = null
            if (r != null) { if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else r.deny() }
        }
    }

    private fun download(url: String, cd: String?, mime: String?) {
        if (!url.startsWith("http")) return // blob e data li gestisce il pezzo di JavaScript qui sopra
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url)).setMimeType(mime).setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Download avviato", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { open(Uri.parse(url)) }
    }

    private fun saveFile(name: String, dataUrl: String) {
        try {
            val comma = dataUrl.indexOf(',')
            val mime = dataUrl.substring(5, dataUrl.indexOf(';').takeIf { it in 0 until comma } ?: comma).ifEmpty { "application/octet-stream" }
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, clean); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception()
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                @Suppress("DEPRECATION") val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), clean)
                f.writeBytes(bytes)
            }
            Toast.makeText(this, "Salvato nei Download: $clean", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Non riesco a salvare il file", Toast.LENGTH_SHORT).show()
        }
    }

    /** Quello che la web app può chiedere al programma. */
    private inner class Bridge {
        @JavascriptInterface fun link(t: String, quiet: Boolean) { if (Store.validToken(t)) runOnUiThread { this@MainActivity.link(t, quiet) } }
        @JavascriptInterface fun notifSettings() {
            runOnUiThread {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                try { startActivity(i) } catch (_: Exception) {}
            }
        }
        @JavascriptInterface fun save(name: String, dataUrl: String) = runOnUiThread { saveFile(name, dataUrl) }
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
    }
}
