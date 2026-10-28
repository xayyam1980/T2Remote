package app.t2remote

import android.content.Context
import android.content.SharedPreferences
import app.t2remote.hid.KeyMap

/** Persistent app configuration. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("t2remote", Context.MODE_PRIVATE)
    }

    var pointerSpeed: Float
        get() = sp.getFloat("pointerSpeed", 1.6f)
        set(v) = sp.edit().putFloat("pointerSpeed", v).apply()

    var scrollSpeed: Float
        get() = sp.getFloat("scrollSpeed", 1.0f)
        set(v) = sp.edit().putFloat("scrollSpeed", v).apply()

    var invertScroll: Boolean
        get() = sp.getBoolean("invertScroll", false)
        set(v) = sp.edit().putBoolean("invertScroll", v).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean("autoReconnect", true)
        set(v) = sp.edit().putBoolean("autoReconnect", v).apply()

    var lastHost: String?
        get() = sp.getString("lastHost", null)
        set(v) = sp.edit().putString("lastHost", v).apply()

    var layoutSwitch: KeyMap.SwitchCombo
        get() = runCatching { KeyMap.SwitchCombo.valueOf(sp.getString("layoutSwitch", "")!!) }
            .getOrDefault(KeyMap.SwitchCombo.CTRL_SPACE)
        set(v) = sp.edit().putString("layoutSwitch", v.name).apply()

    /** Delay between keystrokes when typing (ms). Raise if characters get lost. */
    var keyDelayMs: Int
        get() = sp.getInt("keyDelayMs", 12)
        set(v) = sp.edit().putInt("keyDelayMs", v).apply()

    var textRouteAdb: Boolean
        get() = sp.getBoolean("textRouteAdb", false)
        set(v) = sp.edit().putBoolean("textRouteAdb", v).apply()

    /** Touchpad mode: "auto" (D-pad in TV apps like YouTube/Netflix), "mouse", or "dpad". */
    var touchMode: String
        get() = sp.getString("touchMode", "auto") ?: "auto"
        set(v) = sp.edit().putString("touchMode", v).apply()

    var adbHost: String
        get() = sp.getString("adbHost", "") ?: ""
        set(v) = sp.edit().putString("adbHost", v).apply()

    var adbPort: Int
        get() = sp.getInt("adbPort", 5555)
        set(v) = sp.edit().putInt("adbPort", v).apply()
}
