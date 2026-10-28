package app.t2remote.home

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/**
 * T2 Home — "Cinematic" Android TV home screen.
 * Blurred backdrop of the focused app, large title, big pinned banners, compact apps row.
 * Plain views, no libraries, no background work: tiny memory footprint for 1 GB devices.
 */
class HomeActivity : Activity() {

    private data class App(
        val label: String, val pkg: String, val intent: Intent,
        val banner: Drawable?, val icon: Drawable?,
    )

    private val defaultPins = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.google.android.youtube.tvmusic",
        "com.yandex.browser.tv",
    )

    private lateinit var backdropA: ImageView
    private lateinit var backdropB: ImageView
    private var showingA = true
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var pinRow: LinearLayout
    private lateinit var appsRow: LinearLayout
    private var firstTile: View? = null
    private var lastSignature = ""
    private var appsByPkg: Map<String, App> = emptyMap()

    private val prefs by lazy { getSharedPreferences("home", Context.MODE_PRIVATE) }
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    // ------------------------------------------------------------------ layout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0B0F")) }

        // Two backdrops for crossfading
        backdropA = backdrop(); backdropB = backdrop()
        root.addView(backdropA, MATCH_PARENT, MATCH_PARENT)
        root.addView(backdropB, MATCH_PARENT, MATCH_PARENT)

        // Scrims: dark on the left (for text) and at the bottom (for rows)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#F00B0B0F"), Color.parseColor("#990B0B0F"), Color.parseColor("#200B0B0F")))
        }, MATCH_PARENT, MATCH_PARENT)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#E60B0B0F")))
        }, MATCH_PARENT, MATCH_PARENT)

        // Clock + date, top right
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clockCol.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        })
        clockCol.addView(TextClock(this).apply {
            format24Hour = "EEE d MMM"; format12Hour = "EEE d MMM"
            setTextColor(Color.parseColor("#B3FFFFFF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        root.addView(clockCol, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(28f), dp(48f), 0)
        })

        // Title block, upper left
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        subtitle = TextView(this).apply {
            setTextColor(Color.parseColor("#B3FFFFFF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "OK to open · hold OK to pin"
        }
        titleCol.addView(title); titleCol.addView(subtitle)
        root.addView(titleCol, FrameLayout.LayoutParams(dp(560f), WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(56f), dp(96f), 0, 0)
        })

        // Bottom: pinned banners + apps row
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinRow = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(dp(48f), dp(12f), dp(48f), dp(12f)) }
        bottom.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(pinRow)
        })
        bottom.addView(TextView(this).apply {
            text = "Apps"; setTextColor(Color.parseColor("#99FFFFFF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(56f), dp(4f), 0, 0)
        })
        appsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(48f), dp(8f), dp(48f), dp(28f)) }
        bottom.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(appsRow)
        })
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun backdrop() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
        if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
    }

    override fun onResume() {
        super.onResume()
        build(force = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        firstTile?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* home screen */ }

    // ------------------------------------------------------------------ data

    private fun pinned(): MutableList<String> =
        (prefs.getString("pins", null)?.split(",")?.filter { it.isNotBlank() } ?: defaultPins).toMutableList()

    private fun savePins(p: List<String>) = prefs.edit().putString("pins", p.joinToString(",")).apply()

    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<App>()
        fun add(category: String) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == packageName || !seen.add(ai.packageName)) continue
                val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setComponent(ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                out += App(ri.loadLabel(pm).toString(), ai.packageName, intent, banner, ri.loadIcon(pm))
            }
        }
        add(Intent.CATEGORY_LEANBACK_LAUNCHER)
        add(Intent.CATEGORY_LAUNCHER)
        return out.sortedBy { it.label.lowercase() }
    }

    // ------------------------------------------------------------------ build

    private fun build(force: Boolean) {
        val apps = loadApps()
        val pins = pinned()
        val signature = apps.joinToString { it.pkg } + "|" + pins.joinToString()
        if (!force && signature == lastSignature) return
        lastSignature = signature
        appsByPkg = apps.associateBy { it.pkg }

        val focusedPkg = (currentFocus?.tag as? String)
        pinRow.removeAllViews(); appsRow.removeAllViews(); firstTile = null

        val pinnedApps = pins.mapNotNull { appsByPkg[it] }
        if (pinnedApps.isEmpty()) pinRow.addView(TextView(this).apply {
            text = "Hold OK on an app below to pin it here"; setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        pinnedApps.forEachIndexed { i, a ->
            val t = bannerTile(a); if (i == 0) firstTile = t
            pinRow.addView(t)
        }

        val settings = App("Settings", "settings", Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, null)
        (apps.filter { it.pkg !in pins } + settings).forEach { appsRow.addView(iconTile(it)) }

        val target = focusedPkg?.let { pinRow.findViewWithTag<View>(it) ?: appsRow.findViewWithTag(it) }
            ?: firstTile ?: appsRow.getChildAt(0)
        target?.post { target.requestFocus() }
    }

    // ------------------------------------------------------------------ tiles

    private fun rounded(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color)
        if (stroke > 0) setStroke(dp(stroke.toFloat()), Color.WHITE)
    }

    /** Big 16:9 banner for pinned apps. */
    private fun bannerTile(a: App): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isClickable = true; isLongClickable = true
            tag = a.pkg
            background = rounded(Color.parseColor("#33FFFFFF"), 8f)
            clipToOutline = true
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        if (a.banner != null) {
            frame.addView(ImageView(this).apply {
                setImageDrawable(a.banner); scaleType = ImageView.ScaleType.FIT_XY
                background = rounded(Color.parseColor("#1E252C"), 6f); clipToOutline = true
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = rounded(Color.parseColor("#1E252C"), 6f)
            }
            a.icon?.let { col.addView(ImageView(this).apply { setImageDrawable(it) }, LinearLayout.LayoutParams(dp(56f), dp(56f))) }
            col.addView(label(a.label, 14f))
            frame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        frame.layoutParams = LinearLayout.LayoutParams(dp(208f), dp(117f)).apply { setMargins(dp(10f), 0, dp(10f), 0) }
        wire(frame, a, scale = 1.18f)
        return frame
    }

    /** Small round icon for the apps row. */
    private fun iconTile(a: App): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isClickable = true; isLongClickable = true
            tag = a.pkg
            background = rounded(Color.parseColor("#2A2F36"), 28f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        }
        if (a.icon != null) frame.addView(ImageView(this).apply { setImageDrawable(a.icon) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        else frame.addView(label("⚙", 22f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f)).apply { setMargins(dp(8f), 0, dp(8f), 0) }
        wire(frame, a, scale = 1.25f)
        return frame
    }

    private fun label(s: String, sp: Float) = TextView(this).apply {
        text = s; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        setPadding(dp(6f), dp(4f), dp(6f), 0)
    }

    private fun wire(v: View, a: App, scale: Float) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
            view.elevation = if (has) dp(12f).toFloat() else 0f
            val bg = view.background as? GradientDrawable
            bg?.setStroke(if (has) dp(2f) else 0, Color.WHITE)
            if (has) onAppFocused(a)
        }
        v.setOnClickListener { launch(a) }
        v.setOnLongClickListener { togglePin(a); true }
    }

    private fun onAppFocused(a: App) {
        title.text = a.label
        subtitle.text = if (a.pkg == "settings") "Projector settings"
            else if (a.pkg in pinned()) "OK to open · hold OK to unpin" else "OK to open · hold OK to pin"
        // Crossfade the blurred backdrop to this app's artwork
        val art = a.banner ?: a.icon ?: return
        val incoming = if (showingA) backdropB else backdropA
        val outgoing = if (showingA) backdropA else backdropB
        incoming.setImageDrawable(art.constantState?.newDrawable()?.mutate() ?: art)
        incoming.animate().alpha(0.55f).setDuration(280).start()
        outgoing.animate().alpha(0f).setDuration(280).start()
        showingA = !showingA
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
            (currentFocus?.tag as? String)?.let { appsByPkg[it] }?.let { togglePin(it); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
