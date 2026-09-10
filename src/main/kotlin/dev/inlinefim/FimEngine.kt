package dev.inlinefim

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections

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
 * properties -- roughly Python's @dataclass. The generated equals() is exactly
 * what makes this usable as a cache key below.
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
// Cache
// ---------------------------------------------------------------------------
//
// Identical context must not re-query the model. This happens constantly in
// practice: undo, Esc-then-retype, arrow away and back, or the platform simply
// re-firing for a caret position we have already answered for.
//
// LinkedHashMap with accessOrder=true IS an LRU cache -- removeEldestEntry is
// the eviction hook. No cache library, no dependency.

private const val CACHE_ENTRIES = 256

private val cache: MutableMap<FimContext, String> = Collections.synchronizedMap(
    object : LinkedHashMap<FimContext, String>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<FimContext, String>): Boolean = size > CACHE_ENTRIES
    }
)

fun cachedCompletion(ctx: FimContext): String? = cache[ctx]

fun cacheCompletion(ctx: FimContext, text: String) {
    cache[ctx] = text
}

// ---------------------------------------------------------------------------
// Ollama client
// ---------------------------------------------------------------------------

const val DEFAULT_MODEL = "qwen2.5-coder:1.5b-base"
// 127.0.0.1, deliberately NOT localhost. On Windows `localhost` resolves to ::1
// first, Ollama binds IPv4 only, and every new connection eats ~2s failing over.
// Measured: 2428ms wall vs 101ms for an identical request. See bench/latency.py.
const val OLLAMA_URL = "http://127.0.0.1:11434/api/generate"

/**
 * `object` is a singleton -- Kotlin's built-in equivalent of a module-level
 * instance. One HttpClient for the whole plugin; building one per request would
 * leak a thread pool every keystroke.
 */
object OllamaFim {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /** The last prompt actually sent, so it can be eyeballed without a debugger. */
    @Volatile
    var lastPrompt: String = ""
        private set

    /**
     * Streams the completion, one chunk per token.
     *
     * Streaming is what separates time-to-first-token from total time. With a
     * single blocking call the user waits for the last token before seeing the
     * first; here text appears as soon as the model produces it, which is the
     * number the latency budget is actually about.
     */
    fun stream(
        prompt: String,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = 128,
        url: String = OLLAMA_URL,   // injectable so the cancellation test can point at a stub server
    ): Flow<String> = flow {
        lastPrompt = prompt

        val options = JsonObject().apply {
            addProperty("temperature", 0.2)   // near-greedy; autocomplete wants the likely token, not a creative one
            addProperty("num_predict", maxTokens)
            add("stop", JsonArray().apply { STOP_TOKENS.forEach { add(it) } })
        }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt)
            addProperty("raw", true)          // bypass the chat template -- see fimPrompt above
            addProperty("stream", true)
            addProperty("keep_alive", "30m")  // keeps weights resident; a cold load costs ~4.5s
            add("options", options)
        }

        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        // ofLines() completes as soon as the response headers land, then yields
        // body lines lazily as they arrive. That laziness is what gives us a real
        // first-token moment instead of one lump at the end.
        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofLines()).await()
        if (resp.statusCode() != 200) return@flow

        val lines = resp.body()
        try {
            val iter = lines.iterator()
            // ponytail: hasNext() blocks between tokens, so cancellation lands on
            // the next token boundary rather than instantly. At ~15ms/token that is
            // invisible, and the 10s request timeout bounds the worst case. Switch
            // to BodyHandlers.ofPublisher if it ever stops being invisible.
            while (iter.hasNext()) {
                currentCoroutineContext().ensureActive()
                val line = iter.next()
                if (line.isBlank()) continue
                val obj = JsonParser.parseString(line).asJsonObject
                obj.get("response")?.asString?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                if (obj.get("done")?.asBoolean == true) break
            }
        } finally {
            // Closing here is what actually aborts the socket on cancellation.
            // CancellationTest fails if this line is removed -- verified, not assumed.
            lines.close()
        }
    }.flowOn(Dispatchers.IO)
}
