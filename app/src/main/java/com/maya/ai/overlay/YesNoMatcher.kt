package com.maya.ai.overlay

/**
 * Strict yes/no reading of a spoken answer. Anything that is not clearly "yes" counts as
 * UNCLEAR, and an unclear answer never runs an action.
 */
object YesNoMatcher {

    enum class Answer { YES, NO, UNCLEAR }

    private fun norm(s: String) = s.lowercase().replace("\u093C", "")

    private val NO_WORDS = setOf(
        "nahi", "nahin", "nhi", "nahii", "no", "nope", "na", "mat", "ruko", "ruk", "cancel", "stop",
        "rehne", "chhodo", "chodo", "wait", "नहीं", "नही", "मत", "रुको", "रुक", "रहने", "छोडो", "कैंसल",
    ).map(::norm).toSet()

    /** Words that alone can mean yes. */
    private val STRONG_YES = setOf(
        "haan", "han", "haa", "ha", "yes", "yeah", "yep", "ok", "okay", "theek", "thik", "bilkul", "sure",
        "bhejo", "bhej", "chalao", "chalo", "kar", "karo", "kardo", "jee", "ji",
        "हाँ", "हां", "हा", "जी", "ठीक", "बिल्कुल", "भेजो", "भेज", "चलाओ", "चलो", "करो", "कर", "कर्दो",
    ).map(::norm).toSet()

    /** Harmless filler words allowed next to a yes ("haan bhej do", "theek hai"). */
    private val FILLER = setOf("hai", "do", "de", "dijiye", "please", "plz", "send", "है", "दो", "दे", "दीजिये")
        .map(::norm).toSet()

    fun decide(text: String?): Answer {
        if (text.isNullOrBlank()) return Answer.UNCLEAR
        val tokens = norm(text).split(Regex("[^\\p{L}\\p{M}]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 5) return Answer.UNCLEAR
        if (tokens.any { it in NO_WORDS }) return Answer.NO
        val allKnown = tokens.all { it in STRONG_YES || it in FILLER }
        val hasStrong = tokens.any { it in STRONG_YES }
        return if (allKnown && hasStrong) Answer.YES else Answer.UNCLEAR
    }
}
