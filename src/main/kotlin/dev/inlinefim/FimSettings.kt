package dev.inlinefim

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Every silence rule is a toggle, because "when should this thing shut up" is a
 * matter of taste and the taste is the user's. A tool that suggests constantly
 * gets uninstalled; a tool that suggests never gets uninstalled faster. The only
 * honest answer is to make it adjustable and then measure what people pick.
 *
 * `@Service(APP)` makes this an application-level singleton the platform builds
 * and disposes for us. `@State` + `@Storage` says: persist to inline-fim.xml.
 */
@Service(Service.Level.APP)
@State(name = "InlineFimSettings", storages = [Storage("inline-fim.xml")])
class FimSettings : PersistentStateComponent<FimSettings.State> {

    /**
     * Plain mutable properties with defaults. The platform serialises this by
     * reflection, which is why it is a bare class rather than a data class --
     * it needs a no-arg constructor and settable fields.
     */
    class State {
        var model: String = DEFAULT_MODEL
        var debounceMs: Int = 200
        var maxTokens: Int = 128

        // How many lines a single suggestion may be. Measured motivation: without
        // a cap the model ran to the 128-token limit on every request, producing
        // ~420-character walls of code nobody asked for.
        var maxLines: Int = 4

        var silenceMidWord: Boolean = true
        var silenceTrailingText: Boolean = true
        var silenceLineComplete: Boolean = true
        var silenceInComments: Boolean = true
        var silenceInStrings: Boolean = true
        var silenceEchoOfSuffix: Boolean = true

        var telemetryEnabled: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun get(): State = service<FimSettings>().state
    }
}

/**
 * The Settings UI. `panel { row { ... } }` is the Kotlin UI DSL -- a builder that
 * produces Swing components, so the whole settings screen is declarative rather
 * than a page of addComponent() calls. `bindText`/`bindSelected` wire a widget
 * directly to a property, so Apply and Reset work without any handler code.
 */
class FimConfigurable : BoundConfigurable("Inline FIM") {

    private val state = FimSettings.get()

    override fun createPanel(): DialogPanel = panel {
        group("Model") {
            row("Ollama model:") {
                textField().bindText(state::model).comment(
                    "Must be a fill-in-the-middle capable model. The -base tags support FIM; " +
                        "the -instruct tags are chat-tuned and will write prose into your buffer."
                )
            }
            row("Max tokens per suggestion:") {
                intTextField(1..2048).bindIntText(state::maxTokens)
            }
            row("Max lines per suggestion:") {
                intTextField(1..50).bindIntText(state::maxLines).comment(
                    "A hard ceiling on how much is offered at once."
                )
            }
            row("Debounce (ms):") {
                intTextField(0..2000).bindIntText(state::debounceMs).comment(
                    "How long to sit still before asking the model. Requests fire in typing pauses."
                )
            }
        }

        group("Stay silent when") {
            row { checkBox("Caret is mid-word").bindSelected(state::silenceMidWord) }
            row { checkBox("There is code after the caret on this line").bindSelected(state::silenceTrailingText) }
            row { checkBox("The line already looks finished").bindSelected(state::silenceLineComplete) }
            row { checkBox("Inside a comment").bindSelected(state::silenceInComments) }
            row { checkBox("Inside a string literal").bindSelected(state::silenceInStrings) }
            row {
                checkBox("The suggestion just repeats code that already follows")
                    .bindSelected(state::silenceEchoOfSuffix)
            }
        }

        group("Telemetry") {
            row {
                checkBox("Record accepted / dismissed suggestions").bindSelected(state::telemetryEnabled)
                    .comment(
                        "Appends one JSON object per suggestion to inline-fim/suggestions.jsonl in the " +
                            "IDE log directory. Stays on this machine; nothing is uploaded."
                    )
            }
        }
    }
}
