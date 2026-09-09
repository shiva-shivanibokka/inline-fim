package dev.inlinefim

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException

/**
 * STEP 2: real fill-in-the-middle completions from the local model.
 *
 * Registered on `com.intellij.inline.completion.provider`. The platform calls
 * us; we never call it.
 */
class InlineFimProvider : InlineCompletionProvider {

    override val id = InlineCompletionProviderID("dev.inlinefim.InlineFimProvider")

    // Runs on the UI thread on every keystroke, so it stays trivial.
    // The real gating -- knowing when to stay silent -- is Step 4.
    override fun isEnabled(event: InlineCompletionEvent): Boolean = true

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        // Document text must be read under a read action. `readAction { }` is the
        // suspending version: it yields instead of blocking if a write is in flight.
        val ctx = readAction {
            buildContext(request.document.immutableCharSequence, request.endOffset)
        }

        val started = System.nanoTime()
        val completion = try {
            OllamaFim.complete(fimPrompt(ctx))
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
            return InlineCompletionSuggestion.Empty
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        thisLogger().info(
            "inline-fim: ${elapsedMs}ms  prefix=${ctx.prefix.length}ch suffix=${ctx.suffix.length}ch " +
                "-> ${completion.length}ch"
        )

        // `ifBlank` is stdlib: returns the receiver unless it is blank. Guard
        // against emitting an element that renders as nothing.
        val text = completion.trimEnd()
        if (text.isBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(text))
        }
    }
}
