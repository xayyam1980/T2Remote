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

    override fun onServiceConnected() { instance = this }
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

/** Receives text from `adb shell am broadcast` (protected by the DUMP permission: shell only). */
class TextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Prefer the T2 Keyboard input method when it's active; fall back to the accessibility service.
        T2Ime.instance?.takeIf { it.currentInputConnection != null }?.let { ime ->
            val ok = when {
                intent.hasExtra("b64") -> ime.insert(String(Base64.decode(intent.getStringExtra("b64"), Base64.DEFAULT), Charsets.UTF_8))
                intent.hasExtra("del") -> ime.delete(intent.getIntExtra("del", 1))
                intent.hasExtra("clear") -> ime.clear()
                intent.hasExtra("enter") -> ime.enter()
                intent.hasExtra("release") -> { ime.release(); true }
                else -> false
            }
            resultCode = if (ok) 1 else 3
            return
        }
        if (intent.hasExtra("release")) { resultCode = 1; return }
        val svc = TextService.instance
        val ok = when {
            svc == null -> false
            intent.hasExtra("b64") -> svc.insert(String(Base64.decode(intent.getStringExtra("b64"), Base64.DEFAULT), Charsets.UTF_8))
            intent.hasExtra("del") -> svc.delete(intent.getIntExtra("del", 1))
            intent.hasExtra("clear") -> svc.clear()
            else -> false
        }
        resultCode = if (ok) 1 else if (svc == null) 2 else 3   // 1 ok, 2 service off, 3 no text field
    }
}
