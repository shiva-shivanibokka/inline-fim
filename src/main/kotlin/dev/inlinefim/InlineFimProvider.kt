package dev.inlinefim

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Everything the provider needs to decide and to report, gathered in one read
 * action so the document cannot shift underneath us mid-decision.
 */
private class Decision(
    val ctx: FimContext,
    val silence: SilenceReason?,
    val caretIndent: Int,
    val language: String,
)

/**
 * Extending [DebouncedInlineCompletionProvider] rather than writing a debouncer:
 * the platform explicitly does NOT debounce typing events and ships this base
 * class for exactly this purpose.
 */
class InlineFimProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("dev.inlinefim.InlineFimProvider")

    // Runs on the UI thread on every keystroke, so it stays trivial. The real
    // decision needs the document and the parse tree, which is off-thread work.
    override fun isEnabled(event: InlineCompletionEvent): Boolean = true

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        FimSettings.get().debounceMs.milliseconds

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val settings = FimSettings.get()
        val caret = request.endOffset

        val decision = readAction {
            val text = request.document.immutableCharSequence
            Decision(
                ctx = buildContext(text, caret),
                silence = textSilenceReason(text, caret, settings)
                    ?: psiSilenceReason(request.file, caret, settings),
                caretIndent = caretIndent(text, caret),
                language = request.file.language.displayName,
            )
        }

        // Knowing when NOT to suggest. A tool that suggests constantly gets
        // switched off, so silence is a feature, not a failure to produce output.
        decision.silence?.let { reason ->
            Telemetry.silent(reason, decision.language)
            return InlineCompletionSuggestion.Empty
        }

        val ctx = decision.ctx

        cachedCompletion(ctx)?.let { cached ->
            if (cached.isBlank()) return InlineCompletionSuggestion.Empty
            Telemetry.suggestionShown(
                SuggestionRecord(
                    model = settings.model,
                    language = decision.language,
                    prefixChars = ctx.prefix.length,
                    suffixChars = ctx.suffix.length,
                    suggestionChars = cached.length,
                    suggestionLines = cached.count { it == '\n' } + 1,
                    ttftMs = 0,
                    totalMs = 0,
                    cached = true,
                    truncated = false,
                )
            )
            return InlineCompletionSingleSuggestion.build {
                emit(InlineCompletionGrayTextElement(cached))
            }
        }

        // Collect the whole completion before showing any of it.
        //
        // This is a deliberate reversal of Step 3, and it costs us the streaming
        // effect. The reason: trimming to a line budget and detecting an echo of
        // the suffix are both judgements about the WHOLE suggestion, and you
        // cannot un-show text you have already rendered. Showing a wall of code
        // and retracting it is worse than waiting 200ms more for a good one.
        //
        // Because nothing shows until the last token, the number the user feels
        // is total, not TTFT. The bench said p50 total 210ms and this comment
        // used to call that "inside the budget anyway". Real editing telemetry
        // said 1567ms, because the bench let the model stop early and real use
        // did not: 83% of suggestions hit the line cap. We were paying for 128
        // tokens and showing four lines of them.
        //
        // So stop reading as soon as no further token could change what is
        // displayed. takeWhile cancels the upstream flow, which closes the
        // response body, which aborts the request -- the model stops generating
        // rather than finishing into a buffer nobody reads.
        val started = System.nanoTime()
        var ttftMs = -1L
        val full = StringBuilder()
        var newlines = 0

        try {
            OllamaFim.stream(fimPrompt(ctx), settings.model, settings.maxTokens)
                // trimSuggestion keeps at most maxLines lines and may stop sooner
                // on a dedent, so maxLines newlines is a hard upper bound on what
                // could ever be shown. Everything after it is waste by construction.
                .takeWhile { newlines < settings.maxLines }
                .collect { chunk ->
                    if (ttftMs < 0) ttftMs = (System.nanoTime() - started) / 1_000_000
                    full.append(chunk)
                    newlines += chunk.count { it == '\n' }
                }
        } catch (e: CancellationException) {
            // You typed again and the platform cancelled us. Normal and healthy --
            // but it MUST be rethrown. Swallowing a CancellationException breaks
            // cancellation for everything upstream, and it is the single easiest
            // way to get coroutines wrong.
            throw e
        } catch (e: Exception) {
            // Ollama down, model missing, connection refused. Stay quiet rather
            // than firing an error balloon on every keystroke.
            thisLogger().warn("inline-fim: completion failed", e)
            return InlineCompletionSuggestion.Empty
        }

        val totalMs = (System.nanoTime() - started) / 1_000_000
        val raw = full.toString().trimEnd()
        val trimmed = trimSuggestion(raw, decision.caretIndent, settings.maxLines)
        val truncated = trimmed.length < raw.length

        // The model can see the suffix, and sometimes decides the likeliest
        // continuation IS the suffix. Accepting that gives you the line twice.
        if (settings.silenceEchoOfSuffix && isEchoOfSuffix(trimmed, ctx.suffix)) {
            cacheCompletion(ctx, "")
            Telemetry.silent(SilenceReason.ECHO_OF_SUFFIX, decision.language)
            return InlineCompletionSuggestion.Empty
        }

        cacheCompletion(ctx, trimmed)
        if (trimmed.isBlank()) return InlineCompletionSuggestion.Empty

        thisLogger().info(
            "inline-fim: ttft=${ttftMs}ms total=${totalMs}ms " +
                "prefix=${ctx.prefix.length}ch suffix=${ctx.suffix.length}ch " +
                "-> ${raw.length}ch trimmed=${trimmed.length}ch"
        )

        Telemetry.suggestionShown(
            SuggestionRecord(
                model = settings.model,
                language = decision.language,
                prefixChars = ctx.prefix.length,
                suffixChars = ctx.suffix.length,
                suggestionChars = trimmed.length,
                suggestionLines = trimmed.count { it == '\n' } + 1,
                ttftMs = ttftMs,
                totalMs = totalMs,
                cached = false,
                truncated = truncated,
            )
        )

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(trimmed))
        }
    }
}
