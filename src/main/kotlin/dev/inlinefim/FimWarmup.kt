package dev.inlinefim

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * Load the model before the user needs it.
 *
 * Measured: a cold Ollama load costs ~4.5s, against ~60ms once the weights are
 * resident. Requests pin the model for 30 minutes via keep_alive, but the first
 * request of a session pays full price -- which is precisely the moment a new
 * user forms their opinion of whether this thing is fast.
 *
 * So we spend one throwaway single-token request at startup and get that cost
 * out of the way while the IDE is still opening files.
 *
 * `ProjectActivity.execute` is a suspend function the platform runs in the
 * background after startup, so this never blocks the UI. If Ollama is not
 * running there is nothing to warm and nothing to complain about: the plugin is
 * simply idle until it is.
 */
class FimWarmup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = FimSettings.get()
        val started = System.nanoTime()
        try {
            // One token is enough to force the load; we throw the result away.
            OllamaFim.stream(
                prompt = fimPrompt(FimContext(prefix = "x = ", suffix = "\n")),
                model = settings.model,
                maxTokens = 1,
            ).collect { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ollama not running, model not pulled, port taken. All fine -- this is
            // an optimisation, not a dependency. Log at info so it is findable
            // without being alarming.
            thisLogger().info("inline-fim: warm-up skipped (${e.javaClass.simpleName}: ${e.message})")
            return
        }
        thisLogger().info(
            "inline-fim: warmed ${settings.model} in ${(System.nanoTime() - started) / 1_000_000}ms"
        )
    }
}
