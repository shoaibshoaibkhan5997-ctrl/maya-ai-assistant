package com.maya.ai.overlay

import org.json.JSONException
import org.json.JSONObject

/** Decides whether a Vosk result means "Hey Maya". Pure logic, so it can be unit tested. */
object WakeWordMatcher {

    /** The recognizer may only output these phrases (or [unk] for everything else). */
    const val GRAMMAR = "[\"hey maya\", \"hay maya\", \"hi maya\", \"[unk]\"]"

    private val GREETINGS = setOf("hey", "hay", "hi")
    private val NAMES = setOf("maya", "maia")

    /** [hypothesis] is Vosk's JSON, e.g. {"text" : "hey maya"}. */
    fun matches(hypothesis: String?): Boolean {
        if (hypothesis.isNullOrBlank()) return false
        val text = try {
            JSONObject(hypothesis).optString("text", "")
        } catch (e: JSONException) {
            hypothesis
        }
        return matchesText(text)
    }

    fun matchesText(text: String): Boolean {
        val tokens = text.lowercase().split(Regex("[^a-z\\[\\]]+")).filter { it.isNotEmpty() }
        return tokens.any { it in GREETINGS } && tokens.any { it in NAMES }
    }
}
