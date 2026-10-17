package com.maya.ai.domain

import org.json.JSONException
import org.json.JSONObject

/**
 * Converts Gemini's JSON output into an allowlisted [AssistantCommand].
 * Unknown types and invalid parameters become [AssistantCommand.Invalid] and are never executed.
 */
object CommandParser {
    const val MAX_APP_NAME = 60
    const val MAX_TEXT = 500

    fun parse(raw: String): AssistantCommand {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        if (cleaned.isEmpty()) return AssistantCommand.Invalid("Khaali jawab mila.")

        val obj = try {
            JSONObject(cleaned)
        } catch (e: JSONException) {
            // Not JSON: treat as plain text answer. Plain text can never operate the phone.
            return AssistantCommand.AnswerQuestion(cleaned)
        }

        fun str(key: String) = if (obj.isNull(key)) "" else obj.optString(key, "").trim()

        val reply = str("reply")
        return when (str("type").uppercase()) {
            "ANSWER_QUESTION" ->
                if (reply.isEmpty()) AssistantCommand.Invalid("Khaali jawab mila.")
                else AssistantCommand.AnswerQuestion(reply)

            "CLARIFY" ->
                if (reply.isEmpty()) AssistantCommand.Invalid("Clarification question missing.")
                else AssistantCommand.Clarify(reply)

            "OPEN_APP" -> {
                val app = str("app")
                if (app.isEmpty() || app.length > MAX_APP_NAME || app.any { it.isISOControl() })
                    AssistantCommand.Invalid("App ka naam samajh nahi aaya.")
                else AssistantCommand.OpenApp(app)
            }

            "SCROLL" -> when (str("direction").uppercase()) {
                "UP" -> AssistantCommand.Scroll(ScrollDirection.UP)
                "DOWN" -> AssistantCommand.Scroll(ScrollDirection.DOWN)
                else -> AssistantCommand.Invalid("Scroll direction UP ya DOWN hona chahiye.")
            }

            "NAVIGATE_BACK" -> AssistantCommand.NavigateBack

            "TYPE_TEXT" -> {
                val text = str("text")
                if (text.isEmpty() || text.length > MAX_TEXT)
                    AssistantCommand.Invalid("Likhne ke liye text khaali ya bahut lamba hai.")
                else AssistantCommand.TypeText(text)
            }

            "STOP_ACTIONS" -> AssistantCommand.StopActions
            else -> AssistantCommand.Invalid("Yeh action supported nahi hai.")
        }
    }
}
