package dev.inlinefim

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile

/**
 * Why we said nothing. Recorded in telemetry, so "how often does this thing shut
 * up, and for which reason" is a question with a real answer rather than a guess.
 */
enum class SilenceReason {
    NO_CONTEXT,
    MID_WORD,
    TRAILING_TEXT,
    LINE_COMPLETE,
    IN_COMMENT,
    IN_STRING,
    ECHO_OF_SUFFIX,
}

// Characters that may sit between the caret and the end of the line without the
// completion being pointless. Closing brackets and separators are fine -- you
// very often want to complete an argument inside foo(|) -- but an identifier
// after the caret means you are editing existing code, not writing new code.
private const val HARMLESS_TRAILING = " \t)]},;:"

/**
 * Rules that need only the raw text. Kept separate from the PSI rules so they can
 * be tested without spinning up a whole IDE fixture.
 *
 * Returns the reason to stay quiet, or null to go ahead.
 */
fun textSilenceReason(text: CharSequence, caret: Int, s: FimSettings.State): SilenceReason? {
    if (caret < 0 || caret > text.length) return SilenceReason.NO_CONTEXT

    val lineStart = (text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).takeIf { it >= 0 }?.plus(1)) ?: 0
    val lineEnd = text.indexOf('\n', caret).takeIf { it >= 0 } ?: text.length

    // Nothing before the caret at all: no signal to complete from.
    if (text.subSequence(0, caret).isBlank()) return SilenceReason.NO_CONTEXT

    // Mid-word: the caret sits inside an identifier. Completing `fiz|zbuzz` means
    // shoving text into the middle of a word the user is already halfway through.
    if (s.silenceMidWord && caret < text.length && text[caret].isWordChar()) {
        return SilenceReason.MID_WORD
    }

    val rest = text.subSequence(caret, lineEnd)

    // Real code after the caret on this line: you are editing, not writing.
    if (s.silenceTrailingText && rest.any { it !in HARMLESS_TRAILING }) {
        return SilenceReason.TRAILING_TEXT
    }

    // The line already looks finished. A trailing `;` or `}` in a brace language
    // is about as close to "this statement is done" as you get without parsing.
    if (s.silenceLineComplete && rest.isBlank()) {
        val line = text.subSequence(lineStart, caret).trimEnd()
        if (line.endsWith(";") || line.endsWith("}")) return SilenceReason.LINE_COMPLETE
    }

    return null
}

/**
 * Rules that need the parse tree: are we inside a comment or a string literal?
 *
 * Must be called under a read action.
 */
fun psiSilenceReason(file: PsiFile, caret: Int, s: FimSettings.State): SilenceReason? {
    if (!s.silenceInComments && !s.silenceInStrings) return null

    // findElementAt returns the leaf covering the offset. At the very end of a
    // file it returns null, so fall back one character.
    val element = file.findElementAt(caret) ?: file.findElementAt((caret - 1).coerceAtLeast(0)) ?: return null

    if (s.silenceInComments) {
        // PsiComment is a platform-wide interface, so this works in every language
        // without knowing anything about the language.
        var node = element
        while (true) {
            if (node is PsiComment) return SilenceReason.IN_COMMENT
            node = node.parent ?: break
        }
    }

    if (s.silenceInStrings) {
        // ponytail: there is no cross-language PsiString interface, so this matches
        // on token type names. Covers Python/Java/Kotlin/JS/Go, will miss an exotic
        // language that names its string token something else. Upgrade path is a
        // per-language strategy keyed on Language, if that ever proves necessary.
        val type = element.node?.elementType?.toString()?.uppercase().orEmpty()
        if (type.contains("STRING") || type.contains("CHAR_LITERAL") || type.contains("TEXT_BLOCK")) {
            return SilenceReason.IN_STRING
        }
    }

    return null
}

/**
 * Would this suggestion just re-type code that already sits below the caret?
 *
 * The model sees the suffix and sometimes decides the most likely continuation is
 * the suffix. Accepting that gives you the same line twice.
 */
fun isEchoOfSuffix(suggestion: String, suffix: String): Boolean {
    val s = suggestion.trim()
    val n = suffix.trim()
    if (s.isEmpty() || n.isEmpty()) return false
    val head = n.lineSequence().firstOrNull()?.trim().orEmpty()
    if (head.isEmpty()) return false
    val firstLine = s.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine == head
}

/**
 * Cut a suggestion down to something a person actually wants offered.
 *
 * Two rules:
 *  - never more than [maxLines] lines;
 *  - stop where the block ends. If the caret is indented and the model dedents
 *    back out past that indentation, it has stopped completing your code and
 *    started writing the next function.
 */
fun trimSuggestion(raw: String, caretIndent: Int, maxLines: Int): String {
    if (raw.isEmpty()) return raw
    val lines = raw.lines()
    val kept = mutableListOf<String>()

    for ((i, line) in lines.withIndex()) {
        if (kept.size >= maxLines) break

        // The first line continues the caret's own line, so its indentation is
        // meaningless -- only judge lines that start fresh.
        if (i > 0 && caretIndent > 0 && line.isNotBlank() && indentOf(line) < caretIndent) break

        kept += line
    }

    return kept.joinToString("\n").trimEnd()
}

private fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

/** Indentation of the line the caret is on. */
fun caretIndent(text: CharSequence, caret: Int): Int {
    val lineStart = (text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).takeIf { it >= 0 }?.plus(1)) ?: 0
    var i = lineStart
    var n = 0
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
        n++; i++
    }
    return n
}

private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'
