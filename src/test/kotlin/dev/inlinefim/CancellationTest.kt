package dev.inlinefim

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cancellation is the one property of this plugin that is easy to claim and easy
 * to get silently wrong. A blocking `send()` inside a coroutine, or a bare
 * `catch (e: Exception)` swallowing CancellationException, both leave the code
 * looking correct while requests carry on running to deliver results nobody
 * wants. Neither mistake throws anything.
 *
 * So rather than asserting it in a README, this test watches from the server
 * side: it streams tokens slowly, cancels the collector part-way, and checks
 * that the server never got to finish. If cancellation did not reach the socket,
 * the stub would happily stream all its lines and the test would fail.
 *
 * A stub HttpServer rather than Ollama, so this runs anywhere, offline, in CI.
 */
class CancellationTest {

    private val totalLines = 40
    private val lineDelayMs = 25L

    @Test
    fun `cancelling the coroutine aborts the in-flight HTTP request`() = runBlocking {
        val linesWritten = AtomicInteger()
        val serverFinished = AtomicBoolean(false)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/generate") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { out ->
                    repeat(totalLines) { i ->
                        out.write("""{"response":"tok$i ","done":false}""".toByteArray())
                        out.write('\n'.code)
                        out.flush()
                        linesWritten.incrementAndGet()
                        Thread.sleep(lineDelayMs)
                    }
                }
                // Only reached if the client stayed for the whole stream.
                serverFinished.set(true)
            } catch (e: IOException) {
                // Client hung up. This is the outcome we are testing for.
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        val url = "http://127.0.0.1:${server.address.port}/api/generate"
        val received = AtomicInteger()

        try {
            val job = launch(Dispatchers.IO) {
                OllamaFim.stream("prompt", url = url).collect { received.incrementAndGet() }
            }

            // Wait until tokens are genuinely flowing, so we know we are cancelling
            // a live request rather than one that never started.
            withTimeout(5_000) {
                while (received.get() < 3) delay(5)
            }

            job.cancelAndJoin()
            assertTrue("job should be cancelled", job.isCancelled)

            val atCancel = linesWritten.get()

            // Wait longer than the ENTIRE stream would take. This matters: if we
            // only waited a little, a broken implementation that kept streaming
            // would still show fewer than totalLines and the test would pass while
            // proving nothing. By waiting past the full duration, the only way the
            // count stays below totalLines is that the socket really was closed.
            delay(lineDelayMs * totalLines + 500)

            assertFalse(
                "server streamed all $totalLines lines, so the request was never aborted",
                serverFinished.get(),
            )
            assertTrue(
                "server kept writing after cancel (was $atCancel, now ${linesWritten.get()}, of $totalLines)",
                linesWritten.get() < totalLines,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a completed stream is not reported as cancelled`() = runBlocking {
        // The negative control. If the test above passed simply because the stub
        // never works, this one would fail too.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/generate") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("""{"response":"hello","done":false}""".toByteArray())
                out.write('\n'.code)
                out.write("""{"response":" world","done":true}""".toByteArray())
                out.write('\n'.code)
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val url = "http://127.0.0.1:${server.address.port}/api/generate"
            val chunks = mutableListOf<String>()
            withTimeout(10_000) {
                OllamaFim.stream("prompt", url = url).collect { chunks += it }
            }
            assertTrue("expected both chunks, got $chunks", chunks.size == 2)
            assertTrue(chunks.joinToString("") == "hello world")
        } finally {
            server.stop(0)
        }
    }
}
