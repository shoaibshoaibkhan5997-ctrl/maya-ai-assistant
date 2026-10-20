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
    const val MAX_QUERY = 100
    const val MAX_LABEL = 60
    const val MAX_STEPS = 6
    private const val MAX_NOTE = 200

    private fun clean(raw: String) = raw.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()

    /**
     * Turns what the user said into a full international number like +919876543210,
     * or null if it does not look like a real phone number (this also blocks 100, 112, etc.).
     */
    fun normalizeNumber(raw: String): String? {
        val s = raw.trim().filter { !it.isWhitespace() && it !in "-()." }
        var intl = s.startsWith("+")
        var d = s.removePrefix("+")
        if (d.startsWith("00")) { d = d.drop(2); intl = true }
        if (d.isEmpty() || !d.all { it.isDigit() }) return null
        return when {
            intl && d.length in 10..15 -> "+$d"
            !intl && d.length == 10 && d[0] in '6'..'9' -> "+91$d"
            !intl && d.length == 11 && d[0] == '0' && d[1] in '6'..'9' -> "+91" + d.drop(1)
            !intl && d.length in 11..15 -> "+$d"
            else -> null
        }
    }

    /** Parts of the request the app cannot do. Empty when everything asked for is supported. */
    fun unsupportedNote(raw: String): String {
        return try {
            val obj = JSONObject(clean(raw))
            if (obj.isNull("unsupported")) return ""
            val note = obj.optString("unsupported", "").trim().take(MAX_NOTE)
            if (note.lowercase() in setOf("none", "null", "n/a", "na", "-")) "" else note
        } catch (e: JSONException) {
            ""
        }
    }

    fun parse(raw: String): AssistantCommand {
        val cleaned = clean(raw)
        if (cleaned.isEmpty()) return AssistantCommand.Invalid("Khaali jawab mila.")

        val obj = try {
            JSONObject(cleaned)
        } catch (e: JSONException) {
            // Not JSON: treat as plain text answer. Plain text can never operate the phone.
            return AssistantCommand.AnswerQuestion(cleaned)
        }
        return parseObject(obj, allowPlan = true)
    }

    private fun parseObject(obj: JSONObject, allowPlan: Boolean): AssistantCommand {
        fun str(key: String) = if (obj.isNull(key)) "" else obj.optString(key, "").trim()
        fun bad(s: String, max: Int) = s.isEmpty() || s.length > max || s.any { it.isISOControl() }

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
                if (bad(app, MAX_APP_NAME)) AssistantCommand.Invalid("App ka naam samajh nahi aaya.")
                else AssistantCommand.OpenApp(app)
            }

            "SEARCH_YOUTUBE" -> {
                val q = str("query")
                if (bad(q, MAX_QUERY)) AssistantCommand.Invalid("Search ke liye text samajh nahi aaya.")
                else AssistantCommand.SearchYoutube(q)
            }

            "SEARCH_IN_APP" -> {
                val q = str("query")
                if (bad(q, MAX_QUERY)) AssistantCommand.Invalid("Search ke liye text samajh nahi aaya.")
                else AssistantCommand.SearchInApp(q)
            }

            "TAP_TEXT" -> {
                val l = str("label")
                if (bad(l, MAX_LABEL)) AssistantCommand.Invalid("Tap karne ka naam samajh nahi aaya.")
                else AssistantCommand.TapText(l)
            }

            "TAP_FIRST_RESULT" -> AssistantCommand.TapFirstResult

            "CALL" -> {
                val n = normalizeNumber(str("number"))
                if (n == null) AssistantCommand.Invalid("Number sahi nahi lagta. 10 digit ya country code ke saath poora number batayein.")
                else AssistantCommand.Call(n)
            }

            "WHATSAPP_MESSAGE", "SMS_MESSAGE" -> {
                val n = normalizeNumber(str("number"))
                val t = str("text")
                if (n == null) {
                    AssistantCommand.Invalid("Number sahi nahi lagta. 10 digit ya country code ke saath poora number batayein.")
                } else if (t.isEmpty() || t.length > MAX_TEXT) {
                    AssistantCommand.Invalid("Message khaali ya bahut lamba hai.")
                } else if (str("type").uppercase() == "SMS_MESSAGE") {
                    AssistantCommand.SmsMessage(n, t)
                } else {
                    AssistantCommand.WhatsAppMessage(n, t)
                }
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

            "PRESS_SEND" -> AssistantCommand.PressSend

            "PLAN" -> {
                val arr = obj.optJSONArray("steps")
                if (!allowPlan || arr == null || arr.length() == 0 || arr.length() > MAX_STEPS) {
                    AssistantCommand.Invalid("Steps ki list sahi nahi hai.")
                } else {
                    val steps = ArrayList<AssistantCommand>()
                    for (i in 0 until arr.length()) {
                        val stepObj = arr.optJSONObject(i)
                            ?: return AssistantCommand.Invalid("Step ${i + 1} samajh nahi aaya.")
                        val step = parseObject(stepObj, allowPlan = false)
                        if (!step.isDeviceAction) {
                            return AssistantCommand.Invalid("Step ${i + 1} supported nahi hai.")
                        }
                        if (step is AssistantCommand.Call || step is AssistantCommand.WhatsAppMessage ||
                            step is AssistantCommand.SmsMessage
                        ) {
                            return AssistantCommand.Invalid("Call ya message ko dusre steps ke saath nahi chala sakte.")
                        }
                        if (step is AssistantCommand.PressSend && i != arr.length() - 1) {
                            return AssistantCommand.Invalid("Send sabse aakhri step hona chahiye.")
                        }
                        steps.add(step)
                    }
                    AssistantCommand.Plan(steps)
                }
            }

            "STOP_ACTIONS" -> AssistantCommand.StopActions
            else -> AssistantCommand.Invalid("Yeh action supported nahi hai.")
        }
    }
}
