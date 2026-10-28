package app.t2remote.home

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/**
 * T2 Home — a minimal Android TV home screen: pinned apps row + all apps grid.
 * Plain views, no libraries, no background work: tiny memory footprint for 1 GB devices.
 */
class HomeActivity : Activity() {

    private data class App(val label: String, val pkg: String, val intent: Intent, val image: Drawable?, val isBanner: Boolean)

    private val defaultPins = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.google.android.youtube.tvmusic",
        "com.yandex.browser.tv",
    )

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var lastSignature = ""
    private var firstTile: View? = null

    private val prefs by lazy { getSharedPreferences("home", Context.MODE_PRIVATE) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1116"))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(28f), dp(48f), dp(40f))
        }
        scroll.addView(content, MATCH_PARENT, WRAP_CONTENT)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build(force = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the first pinned app.
        scroll.smoothScrollTo(0, 0)
        firstTile?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* home screen: nothing to go back to */ }

    // ------------------------------------------------------------------ data

    private fun pinned(): MutableList<String> =
        (prefs.getString("pins", null)?.split(",")?.filter { it.isNotBlank() } ?: defaultPins).toMutableList()

    private fun savePins(p: List<String>) = prefs.edit().putString("pins", p.joinToString(",")).apply()

    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<App>()
        fun add(category: String, preferBanner: Boolean) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == packageName || !seen.add(ai.packageName)) continue
                val banner = if (preferBanner) ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) else null
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setComponent(ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                out += App(ri.loadLabel(pm).toString(), ai.packageName, intent, banner ?: ri.loadIcon(pm), banner != null)
            }
        }
        add(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        add(Intent.CATEGORY_LAUNCHER, false)
        return out.sortedBy { it.label.lowercase() }
    }

    // ------------------------------------------------------------------ UI

    private fun build(force: Boolean) {
        val apps = loadApps()
        val pins = pinned()
        val signature = apps.joinToString { it.pkg } + "|" + pins.joinToString()
        if (!force && signature == lastSignature) return
        lastSignature = signature

        val focusedPkg = (currentFocus?.tag as? String)?.removePrefix("pkg:")
        content.removeAllViews()

        // Header: title + clock
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("T2 Home", 22f, "#7CC4FF"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        })
        content.addView(header)

        val byPkg = apps.associateBy { it.pkg }
        val pinnedApps = pins.mapNotNull { byPkg[it] }

        // Pinned row (large tiles)
        content.addView(sectionTitle("Pinned"))
        val pinRow = LinearLayout(this).apply { setPadding(0, dp(6f), 0, dp(6f)) }
        val pinScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            addView(pinRow)
        }
        if (pinnedApps.isEmpty()) pinRow.addView(text("Hold OK on any app below to pin it here", 15f, "#9AA5B1"))
        firstTile = null
        pinnedApps.forEachIndexed { i, a ->
            val t = tile(a, big = true)
            if (i == 0) firstTile = t
            pinRow.addView(t)
        }
        content.addView(pinScroll)

        // All apps grid (6 per row)
        content.addView(sectionTitle("Apps"))
        val rest = apps.filter { it.pkg !in pins }
        val all = rest + App("Settings", "settings",
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, false)
        all.chunked(6).forEach { chunk ->
            val row = LinearLayout(this).apply { setPadding(0, dp(4f), 0, dp(4f)) }
            chunk.forEach { row.addView(tile(it, big = false)) }
            content.addView(row)
        }

        // Restore focus
        val target = focusedPkg?.let { content.findViewWithTag<View>("pkg:$it") }
            ?: firstTile
            ?: content.findViewWithTag<View>("pkg:settings")
        target?.post { target.requestFocus() }
    }

    private fun sectionTitle(s: String) = text(s, 15f, "#9AA5B1").apply { setPadding(dp(4f), dp(18f), 0, dp(4f)) }

    private fun text(s: String, sp: Float, color: String) = TextView(this).apply {
        text = s; setTextColor(Color.parseColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun tileBackground(): StateListDrawable {
        val normal = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(Color.parseColor("#1E252C")) }
        val focused = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat(); setColor(Color.parseColor("#2C3A47"))
            setStroke(dp(3f), Color.parseColor("#7CC4FF"))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun tile(a: App, big: Boolean): View {
        val w = if (big) dp(240f) else dp(150f)
        val h = if (big) dp(135f) else dp(100f)
        val frame = FrameLayout(this).apply {
            isFocusable = true; isClickable = true; isLongClickable = true
            background = tileBackground()
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            tag = "pkg:${a.pkg}"
        }
        if (a.isBanner && a.image != null) {
            frame.addView(ImageView(this).apply {
                setImageDrawable(a.image); scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            if (a.image != null) col.addView(ImageView(this).apply { setImageDrawable(a.image) },
                LinearLayout.LayoutParams(if (big) dp(64f) else dp(44f), if (big) dp(64f) else dp(44f)))
            else col.addView(text("⚙", if (big) 34f else 26f, "#FFFFFF"))
            col.addView(text(a.label, if (big) 15f else 12f, "#E6E9EC").apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding(dp(4f), dp(4f), dp(4f), 0)
            })
            frame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        frame.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(90).start()
            v.elevation = if (has) dp(8f).toFloat() else 0f
        }
        frame.setOnClickListener { launch(a) }
        frame.setOnLongClickListener { togglePin(a); true }
        val lp = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(8f), dp(8f), dp(8f), dp(8f)) }
        frame.layoutParams = lp
        return frame
    }

    private fun launch(a: App) {
        try { startActivity(a.intent) }
        catch (e: Exception) { Toast.makeText(this, "Can't open ${a.label}", Toast.LENGTH_SHORT).show() }
    }

    private fun togglePin(a: App) {
        if (a.pkg == "settings") return
        val p = pinned()
        val msg = if (p.remove(a.pkg)) "Unpinned ${a.label}" else { p.add(a.pkg); "Pinned ${a.label}" }
        savePins(p)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        build(force = true)
    }

    // MENU key on a tile = pin/unpin, like long-press
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus?.tag as? String)?.removePrefix("pkg:")?.let { pkg ->
                loadApps().firstOrNull { it.pkg == pkg }?.let { togglePin(it); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("unused")
    private fun isInstalled(pkg: String) =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
}
