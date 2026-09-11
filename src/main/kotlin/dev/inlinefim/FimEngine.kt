package dev.inlinefim

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
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
    // <|cursor|> and the chat markers are also in Qwen2.5-Coder's vocabulary and
    // do leak out. bench/offline_eval.py caught this one in the wild:
    //   want: 'er {'   got: 'er() {<|cursor|>'
    // Without the stop it renders as literal garbage in the editor.
    "<|cursor|>", "<|im_start|>", "<|im_end|>",
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

        // ofInputStream() completes as soon as the response headers land, then
        // yields body bytes lazily as they arrive. That laziness is what gives us
        // a real first-token moment instead of one lump at the end.
        //
        // Deliberately NOT ofLines(), which is the obvious choice and hands you a
        // Stream<String> for free. Its stream is backed by a BufferedReader, and
        // BufferedReader synchronises close() on the same lock readLine() holds --
        // so closing it from another thread to abort a stalled read blocks behind
        // the very read it is trying to interrupt. Measured at 4987ms against a
        // 5000ms stall: it waits the whole thing out.
        //
        // The JDK's response InputStream is built for this instead: close() cancels
        // the subscription and pushes a sentinel so a blocked reader wakes up.
        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).await()
        if (resp.statusCode() != 200) return@flow

        val body = resp.body()

        // hasNext() blocks on the socket between tokens, so a cancelled coroutine
        // would sit inside it until the next token arrived before reaching the
        // finally below. That used to be invisible at ~15ms per token, and this
        // comment used to say so.
        //
        // It stopped being invisible. Under a burst of typing, each keystroke
        // abandons a request and starts another; if the abandoned one keeps
        // generating, the new one queues behind it, which makes the next token
        // arrive later, which delays the next cancellation further. Ollama's log
        // caught the feedback loop as request durations climbing 2.4s -> 7.2s
        // across a single burst, all of them overlapping.
        //
        // invokeOnCompletion fires the instant the coroutine is cancelled, on
        // whatever thread cancelled it, so closing the body here unblocks the
        // read immediately instead of one token later.
        // Close the RAW stream, never the reader wrapped around it below -- that
        // is the whole point of the handler change above.
        // onCancelling = true is the entire fix. The default fires when the job
        // COMPLETES, and a cancelled job does not complete until the code inside
        // it unwinds -- which here means until the stalled read returns. The
        // handler would run right after the thing it was meant to interrupt.
        // onCancelling fires the moment cancellation begins instead.
        @OptIn(InternalCoroutinesApi::class)
        val closer = currentCoroutineContext().job.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { runCatching { body.close() } }
        try {
            val reader = body.bufferedReader()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = JsonParser.parseString(line).asJsonObject
                obj.get("response")?.asString?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                if (obj.get("done")?.asBoolean == true) break
            }
        } catch (e: Exception) {
            // Closing the body under a blocked read surfaces as an IO error rather
            // than a cancellation. If we were in fact cancelled, report that --
            // otherwise callers log a scary warning for something we did on purpose.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            closer.dispose()
            // Still closed here for the ordinary paths: completion, and the
            // takeWhile in the provider aborting once it has enough lines.
            // CancellationTest fails if this line is removed -- verified, not assumed.
            runCatching { body.close() }
        }
    }.flowOn(Dispatchers.IO)
}
