package dev.inlinefim

import com.google.gson.JsonObject
import com.intellij.codeInsight.inline.completion.InlineCompletionEventAdapter
import com.intellij.codeInsight.inline.completion.InlineCompletionEventType
import com.intellij.codeInsight.inline.completion.InlineCompletionHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionInstallListener
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * What we know about one suggestion by the time it is shown.
 *
 * Filled in by the provider, finalised by the listener when the user either takes
 * it or throws it away.
 */
data class SuggestionRecord(
    val model: String,
    val language: String,
    val prefixChars: Int,
    val suffixChars: Int,
    val suggestionChars: Int,
    val suggestionLines: Int,
    val ttftMs: Long,
    val totalMs: Long,
    val cached: Boolean,
    val truncated: Boolean,
)

/**
 * Accept-rate telemetry.
 *
 * This is the measurement loop, and it is deliberately the same shape a
 * finetuning pipeline would consume: one JSON object per line, appended, never
 * rewritten. `pandas.read_json(path, lines=True)` and you have a dataframe.
 *
 * Everything stays on this machine. Nothing is uploaded anywhere, and the prompt
 * and completion text are NOT recorded -- only their shape. Accept rate is the
 * signal; your source code is not ours to collect.
 */
object Telemetry {

    private val path: Path by lazy {
        val dir = Path.of(PathManager.getLogPath(), "inline-fim")
        Files.createDirectories(dir)
        dir.resolve("suggestions.jsonl")
    }

    /**
     * The suggestion currently on screen, waiting to be accepted or dismissed.
     *
     * ponytail: a single slot rather than a map keyed by request id, because the
     * platform shows at most one inline session at a time. Correlating by
     * requestId would need @ApiStatus.Internal API for no practical gain.
     */
    private val pending = AtomicReference<SuggestionRecord?>(null)

    fun suggestionShown(record: SuggestionRecord) {
        pending.set(record)
    }

    /** A request we deliberately did not make. Silence is data too. */
    fun silent(reason: SilenceReason, language: String) {
        if (!FimSettings.get().telemetryEnabled) return
        val obj = JsonObject().apply {
            addProperty("ts", Instant.now().toString())
            addProperty("event", "silent")
            addProperty("reason", reason.name.lowercase())
            addProperty("language", language)
        }
        append(obj)
    }

    fun resolve(accepted: Boolean, finishType: String) {
        val record = pending.getAndSet(null) ?: return
        if (!FimSettings.get().telemetryEnabled) return

        val obj = JsonObject().apply {
            addProperty("ts", Instant.now().toString())
            addProperty("event", if (accepted) "accepted" else "dismissed")
            addProperty("finish_type", finishType)
            addProperty("model", record.model)
            addProperty("language", record.language)
            addProperty("prefix_chars", record.prefixChars)
            addProperty("suffix_chars", record.suffixChars)
            addProperty("suggestion_chars", record.suggestionChars)
            addProperty("suggestion_lines", record.suggestionLines)
            addProperty("ttft_ms", record.ttftMs)
            addProperty("total_ms", record.totalMs)
            addProperty("cached", record.cached)
            addProperty("truncated", record.truncated)
        }
        append(obj)
    }

    private fun append(obj: JsonObject) {
        try {
            Files.writeString(
                path,
                obj.toString() + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            // Telemetry must never break the editor. If we cannot write, we lose
            // the data point and move on.
            thisLogger().warn("inline-fim: could not write telemetry", e)
        }
    }

    fun location(): String = path.toString()
}

/**
 * Bridges the platform's inline-completion lifecycle events into [Telemetry].
 *
 * `Insert` fires when the user accepts. `Hide` fires whenever a session ends for
 * any other reason, and carries a FinishType saying which -- Escape, typing on,
 * caret moved, focus lost. That distinction is worth keeping: "dismissed by
 * pressing Escape" is a much stronger negative signal than "dismissed because
 * the editor lost focus".
 */
private class TelemetryListener : InlineCompletionEventAdapter {

    @Volatile
    private var inserted = false

    override fun onInsert(event: InlineCompletionEventType.Insert) {
        inserted = true
        Telemetry.resolve(accepted = true, finishType = "SELECTED")
    }

    override fun onHide(event: InlineCompletionEventType.Hide) {
        if (inserted) {
            inserted = false
            return // already recorded as accepted
        }
        Telemetry.resolve(accepted = false, finishType = event.finishType.name)
    }
}

/**
 * Registered in plugin.xml as an application listener. The platform installs an
 * inline-completion handler per editor; we hook each one as it appears.
 */
class TelemetryInstaller : InlineCompletionInstallListener {
    override fun handlerInstalled(editor: Editor, handler: InlineCompletionHandler) {
        handler.addEventListener(TelemetryListener())
    }
}
