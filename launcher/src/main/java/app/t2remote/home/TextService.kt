package app.t2remote.home

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types text from the T2 Remote phone app into whatever text field has focus — any language,
 * independent of keyboard layouts. Fed by [TextReceiver] (only the ADB shell can send to it).
 */
class TextService : AccessibilityService() {
    companion object { @Volatile var instance: TextService? = null }

    override fun onServiceConnected() { instance = this; TextChannel.start() }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun focusedField(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun current(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString() ?: ""

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        val c = cursor.coerceIn(0, text.length)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, c)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, c)
        })
    }

    fun insert(s: String): Boolean {
        val node = focusedField() ?: return false
        val cur = current(node)
        var a = node.textSelectionStart; var b = node.textSelectionEnd
        if (a < 0 || a > cur.length) a = cur.length
        if (b < a || b > cur.length) b = a
        setText(node, cur.substring(0, a) + s + cur.substring(b), a + s.length)
        return true
    }

    fun delete(n: Int): Boolean {
        val node = focusedField() ?: return false
        val cur = current(node)
        var a = node.textSelectionStart; var b = node.textSelectionEnd
        if (a < 0 || a > cur.length) a = cur.length
        if (b < a || b > cur.length) b = a
        val start = if (b > a) a else (a - n).coerceAtLeast(0)
        setText(node, cur.substring(0, start) + cur.substring(b), start)
        return true
    }

    fun clear(): Boolean { val node = focusedField() ?: return false; setText(node, "", 0); return true }
}

/** Single entry point for typing commands (from the socket channel or a broadcast). Main thread. */
object Typer {
    /** Returns 1 ok, 2 no service, 3 no text field. */
    fun run(cmd: Char, arg: String): Int {
        T2Ime.instance?.takeIf { it.currentInputConnection != null }?.let { ime ->
            val ok = when (cmd) {
                'T' -> ime.insert(arg)
                'D' -> ime.delete(arg.toIntOrNull() ?: 1)
                'C' -> ime.clear()
                'E' -> ime.enter()
                'R' -> { ime.release(); true }
                else -> false
            }
            return if (ok) 1 else 3
        }
        if (cmd == 'R') return 1
        val svc = TextService.instance ?: return 2
        val ok = when (cmd) {
            'T' -> svc.insert(arg)
            'D' -> svc.delete(arg.toIntOrNull() ?: 1)
            'C' -> svc.clear()
            'E' -> false
            else -> false
        }
        return if (ok) 1 else 3
    }
}

/**
 * Local text channel on 127.0.0.1:47321 — reachable only through the phone's ADB link (adb "tcp:" stream),
 * not from the network. One line per command: "T<base64 utf-8>", "D<n>", "C", "E", "R".
 */
object TextChannel {
    const val PORT = 47321
    @Volatile private var started = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        if (started) return
        started = true
        Thread {
            try {
                val server = java.net.ServerSocket(PORT, 4, java.net.InetAddress.getByName("127.0.0.1"))
                while (true) {
                    val sock = server.accept()
                    Thread {
                        runCatching {
                            sock.getInputStream().bufferedReader(Charsets.UTF_8).use { r ->
                                while (true) {
                                    val line = r.readLine() ?: break
                                    if (line.isEmpty()) continue
                                    val cmd = line[0]
                                    val raw = line.substring(1)
                                    val arg = if (cmd == 'T') String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8) else raw
                                    main.post { Typer.run(cmd, arg) }
                                }
                            }
                        }
                        runCatching { sock.close() }
                    }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) { started = false }
        }.apply { isDaemon = true; name = "T2TextChannel" }.start()
    }
}

/** Receives text from `adb shell am broadcast` (protected by the DUMP permission: shell only). */
class TextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = when {
            intent.hasExtra("b64") -> Typer.run('T', String(Base64.decode(intent.getStringExtra("b64"), Base64.DEFAULT), Charsets.UTF_8))
            intent.hasExtra("del") -> Typer.run('D', intent.getIntExtra("del", 1).toString())
            intent.hasExtra("clear") -> Typer.run('C', "")
            intent.hasExtra("enter") -> Typer.run('E', "")
            intent.hasExtra("release") -> Typer.run('R', "")
            else -> 0
        }
    }
}
