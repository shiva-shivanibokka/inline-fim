package dev.inlinefim

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// ---------------------------------------------------------------------------
// Context assembly
// ---------------------------------------------------------------------------
//
// What we send the model, and why.
//
// The naive options are "the whole file" and "the current line". Whole-file is
// wrong because prefill cost scales with prompt length: a 2000-line file would
// blow the latency budget on tokens the model does not need. Current-line is
// wrong because it strips the function signature and local variables the
// completion has to agree with.
//
// So: a character window around the caret, asymmetric. Code depends far more on
// what came before the caret than what comes after, so the prefix gets 3x the
// budget. Both edges snap to line boundaries, because handing a model half an
// identifier teaches it nothing except that the file is corrupt.
//
// These are starting values, not measured optima. Revisit with real latency data.

private const val PREFIX_CHARS = 3000
private const val SUFFIX_CHARS = 1000

/**
 * `data class` generates equals/hashCode/toString/copy from the constructor
 * properties -- roughly Python's @dataclass. The generated equals() is what
 * makes this usable as a cache key later.
 */
data class FimContext(val prefix: String, val suffix: String)

fun buildContext(text: CharSequence, caret: Int): FimContext {
    val start = snapForwardToLine(text, (caret - PREFIX_CHARS).coerceAtLeast(0)).coerceAtMost(caret)
    val end = snapBackwardToLine(text, (caret + SUFFIX_CHARS).coerceAtMost(text.length)).coerceAtLeast(caret)
    return FimContext(
        prefix = text.subSequence(start, caret).toString(),
        suffix = text.subSequence(caret, end).toString(),
    )
}

/** Move [from] forward to the start of the next whole line, so the prefix never begins mid-line. */
private fun snapForwardToLine(text: CharSequence, from: Int): Int {
    if (from <= 0) return 0
    val nl = text.indexOf('\n', from)
    return if (nl < 0) from else nl + 1
}

/** Move [to] back to the end of the last whole line, so the suffix never ends mid-line. */
private fun snapBackwardToLine(text: CharSequence, to: Int): Int {
    if (to >= text.length) return text.length
    val nl = text.lastIndexOf('\n', to)
    return if (nl < 0) to else nl
}

// ---------------------------------------------------------------------------
// FIM prompt
// ---------------------------------------------------------------------------
//
// Qwen2.5-Coder's fill-in-the-middle format, PSM ordering. These are real tokens
// in the model's vocabulary, not text it reads -- which is exactly why this must
// go to Ollama with "raw": true. Without that flag Ollama wraps the string in a
// chat template and the special tokens get mangled into ordinary text.

fun fimPrompt(ctx: FimContext): String =
    "<|fim_prefix|>${ctx.prefix}<|fim_suffix|>${ctx.suffix}<|fim_middle|>"

/**
 * Anything that means "I am done filling the hole". Without these the base model
 * happily keeps generating the next function, the next file, forever.
 */
private val STOP_TOKENS = listOf(
    "<|endoftext|>", "<|fim_pad|>", "<|file_sep|>", "<|repo_name|>",
    "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>",
)

// ---------------------------------------------------------------------------
// Ollama client
// ---------------------------------------------------------------------------

const val DEFAULT_MODEL = "qwen2.5-coder:1.5b-base"

/**
 * `object` is a singleton -- Kotlin's built-in equivalent of a module-level
 * instance. One HttpClient for the whole plugin; building one per request would
 * leak a thread pool every keystroke.
 */
object OllamaFim {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /** The last prompt actually sent, so we can eyeball it without a debugger. */
    @Volatile
    var lastPrompt: String = ""
        private set

    /**
     * Returns the model's completion, or "" if it had nothing / the call failed.
     *
     * `suspend` matters here: the caller is cancelled the moment the user types
     * again, and because we await a CompletableFuture rather than blocking a
     * thread, that cancellation actually aborts the HTTP exchange instead of
     * leaving it running to deliver a result nobody wants.
     */
    suspend fun complete(prompt: String, model: String = DEFAULT_MODEL, maxTokens: Int = 128): String {
        lastPrompt = prompt

        val options = JsonObject().apply {
            addProperty("temperature", 0.2)     // near-greedy; autocomplete wants the likely token, not a creative one
            addProperty("num_predict", maxTokens)
            add("stop", JsonArray().apply { STOP_TOKENS.forEach { add(it) } })
        }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt)
            addProperty("raw", true)           // bypass Ollama's chat template -- see fimPrompt above
            addProperty("stream", false)       // streaming lands in Step 3, where TTFT is the metric
            addProperty("keep_alive", "30m")   // keeps weights resident; a cold load costs ~4.5s
            add("options", options)
        }

        val req = HttpRequest.newBuilder(URI.create("http://localhost:11434/api/generate"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        if (resp.statusCode() != 200) return ""
        return JsonParser.parseString(resp.body()).asJsonObject
            .get("response")?.asString.orEmpty()
    }
}
