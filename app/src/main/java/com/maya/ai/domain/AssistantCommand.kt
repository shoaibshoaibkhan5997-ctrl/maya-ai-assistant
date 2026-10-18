package com.maya.ai.domain

enum class ScrollDirection { UP, DOWN }

/** One turn of conversation sent to Gemini as context. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** Result of trying to run a device action. */
data class ActionResult(val success: Boolean, val message: String)

/** Structured, allowlisted commands. Anything else is rejected by [CommandParser]. */
sealed class AssistantCommand {
    data class AnswerQuestion(val reply: String) : AssistantCommand()
    data class Clarify(val question: String) : AssistantCommand()
    data class OpenApp(val appName: String) : AssistantCommand()
    data class SearchYoutube(val query: String) : AssistantCommand()
    data class SearchInApp(val query: String) : AssistantCommand()
    data class TapText(val label: String) : AssistantCommand()
    data object TapFirstResult : AssistantCommand()
    data class Scroll(val direction: ScrollDirection) : AssistantCommand()
    data object NavigateBack : AssistantCommand()
    data class TypeText(val text: String) : AssistantCommand()
    data class Plan(val steps: List<AssistantCommand>) : AssistantCommand()
    data object StopActions : AssistantCommand()
    data class Invalid(val reason: String) : AssistantCommand()

    /** True for commands that operate the phone through Screen Control. */
    val isDeviceAction: Boolean
        get() = this is OpenApp || this is SearchYoutube || this is SearchInApp ||
            this is TapText || this is TapFirstResult || this is Scroll ||
            this is NavigateBack || this is TypeText || this is Plan

    /** Tapping and typing need an explicit user tap before running. */
    val requiresConfirmation: Boolean
        get() = when (this) {
            is TypeText, is SearchInApp, is TapText, is TapFirstResult -> true
            is Plan -> steps.any { it.requiresConfirmation }
            else -> false
        }

    /** Short Hinglish description shown to the user before and while running. */
    fun describe(): String = when (this) {
        is OpenApp -> "App kholo: $appName"
        is SearchYoutube -> "YouTube par search: \"$query\""
        is SearchInApp -> "Is app mein search karo: \"$query\""
        is TapText -> "Tap karo: \"$label\""
        is TapFirstResult -> "Pehla result tap karo"
        is Scroll -> "Scroll " + if (direction == ScrollDirection.DOWN) "neeche" else "upar"
        is NavigateBack -> "Back jao"
        is TypeText -> "Text likho: \"$text\""
        is Plan -> steps.joinToString(" -> ") { it.describe() }
        else -> ""
    }
}
