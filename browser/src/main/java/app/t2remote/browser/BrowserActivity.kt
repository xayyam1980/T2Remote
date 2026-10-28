package app.t2remote.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream

/**
 * T2 Browser — a lightweight Android TV browser focused on watching video on websites.
 * System WebView, full-screen video, pop-up + ad-network blocking, bookmarks, desktop/mobile mode.
 */
class BrowserActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var url: EditText
    private lateinit var bar: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var fullscreen: FrameLayout
    private lateinit var modeBtn: TextView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var defaultUa = ""

    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    companion object {
        private const val START = "https://start.t2.local/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** Ad / pop-under networks blocked at the network level (domain or any subdomain). */
        private val BLOCK = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "googletagmanager.com", "adnxs.com", "criteo.com", "taboola.com", "outbrain.com",
            "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net", "onclkds.com",
            "exoclick.com", "exdynsrv.com", "juicyads.com", "adsterra.com", "a-ads.com", "hilltopads.net",
            "clickadu.com", "trafficjunky.net", "zeropark.com", "adskeeper.com", "realsrv.com", "tsyndicate.com",
            "bidgear.com", "mgid.com", "marketgid.com", "admitad.com", "betweendigital.com", "acint.net",
            "an.yandex.ru", "yandexadexchange.net", "adfox.ru", "adriver.ru", "ad.mail.ru", "top-fwz1.mail.ru",
            "adhigh.net", "quantserve.com", "scorecardresearch.com", "counter.yadro.ru", "pushnice.com",
            "pushwhy.com", "richpush.co", "rollerads.com", "monetag.com", "galaksion.com", "adcash.com",
        )

        private fun blocked(host: String?): Boolean {
            if (host == null) return false
            var h = host.lowercase()
            while (true) {
                if (h in BLOCK) return true
                val i = h.indexOf('.')
                if (i < 0 || h.indexOf('.', i + 1) < 0) return false
                h = h.substring(i + 1)
            }
        }
        private val EMPTY get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101418")) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Toolbar
        bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181D23"))
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        bar.addView(btn("◀") { if (web.canGoBack()) web.goBack() })
        bar.addView(btn("▶") { if (web.canGoForward()) web.goForward() })
        bar.addView(btn("⟳") { web.reload() })
        bar.addView(btn("⌂") { showStart() })
        url = EditText(this).apply {
            setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#80FFFFFF"))
            hint = "Search or enter address"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.parseColor("#2A313A")) }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        bar.addView(url, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(6f), 0, dp(6f), 0) })
        bar.addView(btn("★") { addBookmark() })
        modeBtn = btn(if (desktop) "Desktop" else "Mobile") { toggleMode() }
        bar.addView(modeBtn)
        col.addView(bar, MATCH_PARENT, WRAP_CONTENT)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        col.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(3f)))

        web = WebView(this)
        col.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(col, MATCH_PARENT, MATCH_PARENT)

        fullscreen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(fullscreen, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)

        setupWeb()
        val start = intent?.dataString
        if (start != null && start.startsWith("http")) web.loadUrl(start)
        else prefs.getString("last", null)?.takeIf { it.startsWith("http") }?.let { web.loadUrl(it) } ?: showStart()
    }

    private val desktop get() = prefs.getBoolean("desktop", true)

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.setBackgroundColor(Color.parseColor("#101418"))
        val s = web.settings
        defaultUa = s.userAgentString
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true); s.builtInZoomControls = true; s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.javaScriptCanOpenWindowsAutomatically = false   // no pop-ups
        s.setSupportMultipleWindows(false)                // target=_blank opens in place
        s.userAgentString = if (desktop) DESKTOP_UA else defaultUa
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (blocked(request.url.host)) EMPTY else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "t2") { handleInternal(u); return true }
                if (u.toString().startsWith(START)) return true
                if (u.scheme != "http" && u.scheme != "https") return true      // block intent:, market:, etc.
                if (blocked(u.host)) return true
                return false
            }

            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                if (u != null && u.startsWith("http") && !u.startsWith(START) && !url.hasFocus()) url.setText(u)
            }

            // Low-memory devices: if the web engine dies, rebuild instead of crashing the app.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                Toast.makeText(this@BrowserActivity, "Page reloaded (low memory)", Toast.LENGTH_SHORT).show()
                recreate()
                return true
            }

            override fun onPageFinished(view: WebView, u: String?) {
                progress.visibility = View.GONE
                if (u != null && u.startsWith("http") && !u.startsWith(START)) prefs.edit().putString("last", u).apply()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) { progress.progress = p }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view; customCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                fullscreen.visibility = View.VISIBLE
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                view.requestFocus()
            }

            override fun onHideCustomView() = exitFullscreen()
        }
    }

    // ------------------------------------------------------------------ navigation

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(target)
        web.requestFocus()
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreen.removeView(v); fullscreen.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden(); customCallback = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        web.requestFocus()
    }

    private fun toggleMode() {
        val d = !desktop
        prefs.edit().putBoolean("desktop", d).apply()
        web.settings.userAgentString = if (d) DESKTOP_UA else defaultUa
        modeBtn.text = if (d) "Desktop" else "Mobile"
        Toast.makeText(this, if (d) "Desktop sites" else "Mobile sites", Toast.LENGTH_SHORT).show()
        web.reload()
    }

    // ------------------------------------------------------------------ bookmarks + start page

    private fun bookmarks(): MutableList<Pair<String, String>> =
        (prefs.getString("bookmarks", "") ?: "").lines().filter { it.contains('\t') }
            .map { it.substringBefore('\t') to it.substringAfter('\t') }.toMutableList()

    private fun saveBookmarks(b: List<Pair<String, String>>) =
        prefs.edit().putString("bookmarks", b.joinToString("\n") { "${it.first}\t${it.second}" }).apply()

    private fun addBookmark() {
        val u = web.url ?: return
        if (!u.startsWith("http")) return
        val b = bookmarks()
        if (b.any { it.second == u }) { Toast.makeText(this, "Already saved", Toast.LENGTH_SHORT).show(); return }
        val title = (web.title?.takeIf { it.isNotBlank() } ?: Uri.parse(u).host ?: u).replace('\t', ' ').replace('\n', ' ')
        b.add(title to u); saveBookmarks(b)
        Toast.makeText(this, "Saved: $title", Toast.LENGTH_SHORT).show()
    }

    private fun handleInternal(u: Uri) {
        when (u.host) {
            "remove" -> { val target = u.getQueryParameter("u"); saveBookmarks(bookmarks().filter { it.second != target }); showStart() }
            "search" -> go(u.getQueryParameter("q") ?: "")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun showStart() {
        url.setText("")
        val tiles = bookmarks().joinToString("") { (t, u) ->
            val host = Uri.parse(u).host ?: ""
            """<div class="t"><a class="b" href="${esc(u)}"><div class="i">${esc(host.take(1).uppercase())}</div>
               <div class="n">${esc(t)}</div><div class="h">${esc(host)}</div></a>
               <a class="x" href="t2://remove?u=${Uri.encode(u)}">✕</a></div>"""
        }.ifEmpty { """<p class="e">No bookmarks yet. Open a site and press ★ to save it here.</p>""" }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>
body{background:#101418;color:#e6e9ec;font-family:sans-serif;margin:0;padding:40px 56px}
h1{font-weight:500;color:#7cc4ff;margin:0 0 24px}
form{display:flex;gap:10px;margin-bottom:32px}
input{flex:1;font-size:20px;padding:14px 20px;border-radius:28px;border:2px solid #2a313a;background:#1e252c;color:#fff}
input:focus{border-color:#7cc4ff;outline:none}
button{font-size:18px;padding:0 24px;border-radius:28px;border:0;background:#7cc4ff;color:#0b2236}
.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:18px}
.t{position:relative}
.b{display:block;background:#1e252c;border-radius:14px;padding:18px;text-decoration:none;color:#e6e9ec;border:3px solid transparent}
.b:focus,.b:hover{border-color:#7cc4ff;outline:none;background:#26303a}
.i{width:44px;height:44px;border-radius:12px;background:#2c3a47;color:#7cc4ff;font-size:22px;display:flex;align-items:center;justify-content:center;margin-bottom:10px}
.n{font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.h{font-size:12px;color:#9aa5b1;margin-top:4px}
.x{position:absolute;top:8px;right:12px;color:#9aa5b1;text-decoration:none;font-size:16px;padding:4px 8px;border-radius:8px}
.x:focus,.x:hover{background:#3a2a2a;color:#ff8080;outline:none}
.e{color:#9aa5b1}
</style></head><body>
<h1>T2 Browser</h1>
<form action="t2://search" method="get"><input name="q" placeholder="Search or enter address" autofocus><button>Go</button></form>
<div class="g">$tiles</div></body></html>"""
        web.loadDataWithBaseURL(START, html, "text/html", "utf-8", null)
    }

    // ------------------------------------------------------------------ keys

    private fun js(code: String) = web.evaluateJavascript(code, null)
    private val V = "(document.querySelector('video'))"

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { js("var v=$V;if(v){v.paused?v.play():v.pause()}"); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { js("var v=$V;if(v)v.currentTime+=30"); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { js("var v=$V;if(v)v.currentTime-=10"); return true }
                KeyEvent.KEYCODE_MENU -> { url.requestFocus(); return true }
            }
            if (customView != null) when (event.keyCode) {
                // Full-screen video: arrows seek / change volume on page-level players
                KeyEvent.KEYCODE_DPAD_RIGHT -> { js("var v=$V;if(v)v.currentTime+=10"); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { js("var v=$V;if(v)v.currentTime-=10"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { js("var v=$V;if(v){v.paused?v.play():v.pause()}"); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            url.hasFocus() -> web.requestFocus()
            web.canGoBack() -> web.goBack()
            web.url?.startsWith(START) != true -> showStart()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.startsWith("http") }?.let { web.loadUrl(it) }
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }

    // ------------------------------------------------------------------ ui helpers

    private fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        minWidth = dp(44f); setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.parseColor("#3A4A5C")) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.TRANSPARENT) })
        }
        setOnClickListener { onClick() }
    }
}
