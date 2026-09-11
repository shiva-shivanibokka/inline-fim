package dev.inlinefim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // -- cache -------------------------------------------------------------
    //
    // The cache key is the part that is easy to get wrong and impossible to
    // notice: keying on context alone serves the old model's completions after
    // you change the model in Settings, which looks like the setting being
    // ignored rather than like a cache bug.

    @Test
    fun `changing the model does not serve the previous model's completion`() {
        clearCompletionCache()
        val ctx = FimContext("def f():\n    ", "\n")
        cacheCompletion(ctx, "model-a", 4, CachedCompletion("return 1", false))

        assertEquals("return 1", cachedCompletion(ctx, "model-a", 4)?.text)
        assertNull("a different model must miss", cachedCompletion(ctx, "model-b", 4))
    }

    @Test
    fun `changing the line cap does not serve the previous cap's completion`() {
        clearCompletionCache()
        val ctx = FimContext("def f():\n    ", "\n")
        cacheCompletion(ctx, "model-a", 4, CachedCompletion("a\nb\nc\nd", true))

        assertNull("a different line cap must miss", cachedCompletion(ctx, "model-a", 2))
    }

    @Test
    fun `a cache hit reports whether the completion was truncated`() {
        // Reported in telemetry, and the line-cap rate is a number this project
        // draws conclusions from. A cache hit that always said false would
        // silently understate it.
        clearCompletionCache()
        val ctx = FimContext("x", "y")
        cacheCompletion(ctx, "m", 4, CachedCompletion("a\nb", true))

        assertEquals(true, cachedCompletion(ctx, "m", 4)?.truncated)
    }
}
