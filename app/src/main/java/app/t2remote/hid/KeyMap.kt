package app.t2remote.hid

/**
 * Maps characters to HID usage codes for the two physical layouts configured on the projector:
 * English (US QWERTY) and Russian (ЙЦУКЕН). HID sends key *positions*; the projector's active
 * layout decides the character, so we switch layout when a character needs the other one.
 */
object KeyMap {
    enum class Layout { EN, RU }

    /** Shortcut the projector uses to cycle physical keyboard layouts. */
    enum class SwitchCombo(val label: String, val mod: Int, val key: Int) {
        CTRL_SPACE("Ctrl + Space", CTRL, 0x2C),
        META_SPACE("Win/Meta + Space", META, 0x2C),
        SHIFT_SPACE("Shift + Space", SHIFT, 0x2C),
        ALT_SHIFT("Alt + Shift", ALT or SHIFT, 0x00),
    }

    data class Stroke(val mod: Int, val code: Int)

    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val META = 0x08

    // Non-character keys
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val DELETE = 0x4C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val HOME = 0x4A
    const val END = 0x4D
    const val PAGE_UP = 0x4B
    const val PAGE_DOWN = 0x4E
    const val F5 = 0x3E

    private val en = HashMap<Char, Stroke>()
    private val ru = HashMap<Char, Stroke>()

    init {
        for (i in 0 until 26) {
            en['a' + i] = Stroke(0, 0x04 + i)
            en['A' + i] = Stroke(SHIFT, 0x04 + i)
        }
        "1234567890".forEachIndexed { i, c -> Stroke(0, 0x1E + i).let { en[c] = it; ru[c] = it } }
        "!@#$%^&*()".forEachIndexed { i, c -> en[c] = Stroke(SHIFT, 0x1E + i) }
        "!\"№;%:?*()".forEachIndexed { i, c -> ru[c] = Stroke(SHIFT, 0x1E + i) }

        for (m in listOf(en, ru)) {
            m[' '] = Stroke(0, SPACE); m['\n'] = Stroke(0, ENTER); m['\t'] = Stroke(0, TAB)
        }

        // US punctuation: unshifted / shifted on the same key
        val enPunct = listOf(
            0x2D to "-_", 0x2E to "=+", 0x2F to "[{", 0x30 to "]}", 0x31 to "\\|",
            0x33 to ";:", 0x34 to "'\"", 0x35 to "`~", 0x36 to ",<", 0x37 to ".>", 0x38 to "/?",
        )
        for ((code, pair) in enPunct) {
            en[pair[0]] = Stroke(0, code); en[pair[1]] = Stroke(SHIFT, code)
        }

        // Russian ЙЦУКЕН: Cyrillic letter -> US key in the same position
        val ruLetters = "йцукенгшщзхъфывапролджэячсмитьбюё"
        val enKeys = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
        ruLetters.forEachIndexed { i, c ->
            val code = en[enKeys[i]]!!.code
            ru[c] = Stroke(0, code)
            ru[c.uppercaseChar()] = Stroke(SHIFT, code)
        }
        ru['-'] = Stroke(0, 0x2D); ru['_'] = Stroke(SHIFT, 0x2D)
        ru['='] = Stroke(0, 0x2E); ru['+'] = Stroke(SHIFT, 0x2E)
        ru['\\'] = Stroke(0, 0x31); ru['/'] = Stroke(SHIFT, 0x31)
        ru['.'] = Stroke(0, 0x38); ru[','] = Stroke(SHIFT, 0x38)

        // Typographic characters phones like to insert
        en['’'] = en['\'']!!; en['‘'] = en['\'']!!; en['“'] = en['"']!!; en['”'] = en['"']!!
        en['—'] = en['-']!!; en['–'] = en['-']!!
        ru['«'] = ru['"']!!; ru['»'] = ru['"']!!; ru['—'] = ru['-']!!; ru['–'] = ru['-']!!
    }

    /**
     * Returns the layout the character must be typed in plus its stroke.
     * Prefers [current] to avoid needless layout switches. Null if not typeable.
     */
    fun resolve(c: Char, current: Layout): Pair<Layout, Stroke>? {
        val primary = if (current == Layout.EN) en else ru
        primary[c]?.let { return current to it }
        val other = if (current == Layout.EN) Layout.RU else Layout.EN
        val secondary = if (other == Layout.EN) en else ru
        return secondary[c]?.let { other to it }
    }
}
