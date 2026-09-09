package dev.inlinefim

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion

/**
 * STEP 1: prove the wiring. One hardcoded suggestion, no model.
 *
 * Registered in plugin.xml under the `com.intellij.inline.completion.provider`
 * extension point. The platform calls us; we never call it.
 */
class InlineFimProvider : InlineCompletionProvider {

    // `override val id = ...` — Kotlin declares a read-only property and infers its
    // type. The interface asks for `val id: InlineCompletionProviderID`, so no type
    // annotation is needed here. This string MUST match id= in plugin.xml.
    override val id = InlineCompletionProviderID("dev.inlinefim.InlineFimProvider")

    // Runs on the UI thread on every keystroke, so it must stay trivial.
    // Real gating (the silence rules) happens in getSuggestion, off the UI thread.
    // `= true` is expression-body syntax: shorthand for `{ return true }`.
    override fun isEnabled(event: InlineCompletionEvent): Boolean = true

    // `suspend` marks a coroutine function: it can pause without blocking a thread.
    // The platform cancels this coroutine when you type again, which is how we get
    // request cancellation for free in STEP 3.
    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion =
        // Trailing-lambda syntax: the `{ }` is the last argument to build().
        // Inside it, `this` is a FlowCollector, so `emit` is in scope unqualified.
        InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement("  # hello from inline-fim"))
        }
}
