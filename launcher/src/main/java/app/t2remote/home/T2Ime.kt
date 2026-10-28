package app.t2remote.home

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * "T2 Keyboard": invisible input method used while the phone's Keyboard tab is open.
 * Text from the phone is committed through the real input connection — any language,
 * no on-screen keyboard in the way. Hands back to the previous keyboard when released or idle.
 */
class T2Ime : InputMethodService() {
    companion object {
        @Volatile var instance: T2Ime? = null
        private const val IDLE_MS = 120_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { release() }

    override fun onCreate() { super.onCreate(); instance = this; TextChannel.start(); touch() }
    override fun onDestroy() { if (instance === this) instance = null; main.removeCallbacks(idle); super.onDestroy() }

    // Never show an on-screen keyboard.
    override fun onEvaluateInputViewShown(): Boolean = false
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) { super.onStartInput(attribute, restarting); touch() }

    private fun touch() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS) }

    fun insert(s: String): Boolean = run { touch(); currentInputConnection?.commitText(s, 1) ?: false }

    fun delete(n: Int): Boolean {
        touch()
        val ic = currentInputConnection ?: return false
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) return ic.commitText("", 1)
        repeat(n) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        return true
    }

    fun clear(): Boolean {
        touch()
        val ic = currentInputConnection ?: return false
        ic.performContextMenuAction(android.R.id.selectAll)
        return ic.commitText("", 1)
    }

    fun enter(): Boolean {
        touch()
        val ic = currentInputConnection ?: return false
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        return if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(action)
        else { sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER); true }
    }

    /** Switch back to the keyboard that was active before (Gboard etc.). */
    fun release() {
        main.removeCallbacks(idle)
        runCatching { switchToPreviousInputMethod() }
    }
}
