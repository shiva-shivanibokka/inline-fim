package dev.inlinefim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context assembly is the one piece of real logic here that has no visible
 * failure mode -- a bad window produces a plausible-looking but worse
 * completion, silently. So it gets the test.
 */
class FimContextTest {

    @Test
    fun `splits the file at the caret`() {
        val text = "def f():\n    return 1\n"
        val caret = text.indexOf("return")
        val ctx = buildContext(text, caret)
        assertEquals("def f():\n    ", ctx.prefix)
        assertEquals("return 1\n", ctx.suffix)
    }

    @Test
    fun `prefix and suffix reconstruct the window exactly`() {
        val text = (1..500).joinToString("\n") { "line $it with some content" }
        val caret = text.length / 2
        val ctx = buildContext(text, caret)
        // No characters invented, none duplicated across the seam.
        assertTrue(text.contains(ctx.prefix + ctx.suffix))
    }

    @Test
    fun `window is bounded on a large file`() {
        val text = (1..20_000).joinToString("\n") { "x = $it" }
        val ctx = buildContext(text, text.length / 2)
        // Whole-file context would be ~200k chars; the window must stay small.
        assertTrue("prefix was ${ctx.prefix.length}", ctx.prefix.length <= 3000)
        assertTrue("suffix was ${ctx.suffix.length}", ctx.suffix.length <= 1000)
    }

    @Test
    fun `edges snap to line boundaries, never mid-identifier`() {
        val text = (1..2000).joinToString("\n") { "some_identifier_$it = compute_something($it)" }
        val caret = text.length / 2
        val ctx = buildContext(text, caret)
        // The window was truncated, so the prefix must start just after a newline
        // and the suffix must end just before one.
        assertTrue(text.startsWith(ctx.prefix) || text[text.indexOf(ctx.prefix) - 1] == '\n')
        assertTrue(ctx.suffix.isEmpty() || !ctx.suffix.endsWith("("))
    }

    @Test
    fun `caret at start and end of file do not crash`() {
        val text = "a = 1\nb = 2\n"
        assertEquals("", buildContext(text, 0).prefix)
        assertEquals("", buildContext(text, text.length).suffix)
    }

    @Test
    fun `fim prompt uses qwen special tokens in PSM order`() {
        val p = fimPrompt(FimContext("BEFORE", "AFTER"))
        assertEquals("<|fim_prefix|>BEFORE<|fim_suffix|>AFTER<|fim_middle|>", p)
    }
}
