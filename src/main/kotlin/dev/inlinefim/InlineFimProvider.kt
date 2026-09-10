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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long to sit still before asking the model anything.
 *
 * Too low and every keystroke fires a request that the next keystroke cancels;
 * the model does work nobody sees and the machine gets hot. Too high and the
 * suggestion feels laggy even when the model is fast. 200ms is roughly the gap
 * between characters in fluent typing, so it fires in the pauses where you are
 * actually thinking -- which is when a suggestion is worth having.
 */
private val DEBOUNCE = 200.milliseconds

/**
 * Extending [DebouncedInlineCompletionProvider] rather than writing a debouncer:
 * the platform explicitly does NOT debounce typing events itself, and ships this
 * base class for exactly this reason.
 */
class InlineFimProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("dev.inlinefim.InlineFimProvider")

    // Runs on the UI thread on every keystroke, so it stays trivial.
    // The real gating -- knowing when to stay silent -- is Step 4.
    override fun isEnabled(event: InlineCompletionEvent): Boolean = true

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration = DEBOUNCE

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        // Document text must be read under a read action. `readAction { }` is the
        // suspending version: it yields instead of blocking if a write is in flight.
        val ctx = readAction {
            buildContext(request.document.immutableCharSequence, request.endOffset)
        }

        // Cache hit: no network, no model, no waiting.
        cachedCompletion(ctx)?.let { cached ->
            thisLogger().info("inline-fim: cache hit -> ${cached.length}ch")
            return if (cached.isBlank()) {
                InlineCompletionSuggestion.Empty
            } else {
                InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(cached)) }
            }
        }

        return InlineCompletionSingleSuggestion.build {
            val started = System.nanoTime()
            var ttftMs = -1L
            val full = StringBuilder()

            // Trailing whitespace is held back rather than emitted, so a completion
            // that ends in a newline does not render a stray blank line under the
            // caret. Whitespace is flushed only once real text follows it.
            val pending = StringBuilder()

            try {
                OllamaFim.stream(fimPrompt(ctx)).collect { chunk ->
                    if (ttftMs < 0) ttftMs = (System.nanoTime() - started) / 1_000_000
                    full.append(chunk)

                    if (chunk.isBlank()) {
                        pending.append(chunk)
                    } else {
                        if (pending.isNotEmpty()) {
                            emit(InlineCompletionGrayTextElement(pending.toString()))
                            pending.setLength(0)
                        }
                        emit(InlineCompletionGrayTextElement(chunk))
                    }
                }
            } catch (e: CancellationException) {
                // You typed again and the platform cancelled us. This is the normal,
                // healthy path -- but it MUST be rethrown. Swallowing a
                // CancellationException breaks coroutine cancellation for everything
                // upstream, and it is the single easiest way to get this wrong.
                throw e
            } catch (e: Exception) {
                // Ollama down, model missing, connection refused. Stay quiet rather
                // than firing an error balloon on every keystroke.
                thisLogger().warn("inline-fim: completion failed", e)
                return@build
            }

            val totalMs = (System.nanoTime() - started) / 1_000_000
            val text = full.toString().trimEnd()
            cacheCompletion(ctx, text)

            // One line per served suggestion. This is the raw material for the
            // latency numbers in the README -- measured, not estimated.
            thisLogger().info(
                "inline-fim: ttft=${ttftMs}ms total=${totalMs}ms " +
                    "prefix=${ctx.prefix.length}ch suffix=${ctx.suffix.length}ch -> ${text.length}ch"
            )
        }
    }
}
