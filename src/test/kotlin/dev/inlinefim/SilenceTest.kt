package dev.inlinefim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silence rules decide when the plugin says nothing. Getting them wrong is
 * invisible in the worst way: too eager and the tool is noise, too shy and it
 * looks broken. Neither throws an exception, so both get tests.
 */
class SilenceTest {

    private fun defaults() = FimSettings.State()

    // -- mid-word ----------------------------------------------------------

    @Test
    fun `silent when the caret sits inside a word`() {
        val text = "value = fizzbuzz(3)\n"
        val caret = text.indexOf("zzbuzz")  // fi|zzbuzz
        assertEquals(SilenceReason.MID_WORD, textSilenceReason(text, caret, defaults()))
    }

    @Test
    fun `speaks at the end of a word`() {
        val text = "value = fizzbuzz"
        assertNull(textSilenceReason(text, text.length, defaults()))
    }

    @Test
    fun `mid-word rule can be switched off`() {
        val text = "value = fizzbuzz(3)\n"
        val caret = text.indexOf("zzbuzz")
        val s = defaults().apply { silenceMidWord = false; silenceTrailingText = false }
        assertNull(textSilenceReason(text, caret, s))
    }

    // -- trailing text -----------------------------------------------------

    @Test
    fun `silent when real code follows on the same line`() {
        val text = "total = compute() + tail\n"
        val caret = text.indexOf(" + tail")
        assertEquals(SilenceReason.TRAILING_TEXT, textSilenceReason(text, caret, defaults()))
    }

    @Test
    fun `speaks inside empty brackets`() {
        // foo(|) is a completely reasonable place to want an argument.
        val text = "result = foo()"
        val caret = text.indexOf(')')
        assertNull(textSilenceReason(text, caret, defaults()))
    }

    // -- line already finished ---------------------------------------------

    @Test
    fun `silent at the end of a finished statement`() {
        val text = "int x = compute();"
        assertEquals(SilenceReason.LINE_COMPLETE, textSilenceReason(text, text.length, defaults()))
    }

    @Test
    fun `speaks at the end of an unfinished line`() {
        val text = "int x = compute("
        assertNull(textSilenceReason(text, text.length, defaults()))
    }

    // -- nothing to go on ---------------------------------------------------

    @Test
    fun `silent with an empty prefix`() {
        assertEquals(SilenceReason.NO_CONTEXT, textSilenceReason("   \n  ", 5, defaults()))
    }

    // -- echo of the suffix -------------------------------------------------

    @Test
    fun `detects a suggestion that just repeats the next line`() {
        assertTrue(isEchoOfSuffix("    return -1", "\n    return -1\n"))
    }

    @Test
    fun `does not flag a genuinely different suggestion`() {
        assertTrue(!isEchoOfSuffix("    lo = mid + 1", "\n    return -1\n"))
    }

    // -- length and block trimming ------------------------------------------

    @Test
    fun `caps the number of lines`() {
        val raw = (1..20).joinToString("\n") { "        line$it()" }
        val out = trimSuggestion(raw, caretIndent = 8, maxLines = 4)
        assertEquals(4, out.lines().size)
    }

    @Test
    fun `stops when the model dedents out of the block`() {
        // Completing inside an indented body; the model carries on into the next
        // top-level def. We want the body, not the next function.
        val raw = "arr[mid] == target\n        return mid\n\ndef other():\n    pass"
        val out = trimSuggestion(raw, caretIndent = 8, maxLines = 10)
        assertTrue("should not reach the next def, got: $out", !out.contains("def other"))
        assertTrue(out.contains("return mid"))
    }

    @Test
    fun `keeps the first line regardless of its indentation`() {
        // The first line continues the caret's own line, so its leading
        // whitespace says nothing about block structure.
        val raw = "target:\n        return mid"
        val out = trimSuggestion(raw, caretIndent = 8, maxLines = 4)
        assertTrue(out.startsWith("target:"))
    }

    @Test
    fun `an empty suggestion survives trimming`() {
        assertEquals("", trimSuggestion("", caretIndent = 0, maxLines = 4))
    }

    // -- caret indentation --------------------------------------------------

    @Test
    fun `measures the indentation of the caret line`() {
        val text = "def f():\n        deep = 1\n"
        assertEquals(8, caretIndent(text, text.indexOf("deep") + 2))
        assertEquals(0, caretIndent(text, 3))
    }
}
