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
    data class Call(val number: String) : AssistantCommand()
    data class WhatsAppMessage(val number: String, val text: String) : AssistantCommand()
    data class SmsMessage(val number: String, val text: String) : AssistantCommand()
    data class Scroll(val direction: ScrollDirection) : AssistantCommand()
    data object NavigateBack : AssistantCommand()
    data class TypeText(val text: String) : AssistantCommand()
    /** Presses Send in a messaging app. Always needs a separate, explicit user confirmation. */
    data object PressSend : AssistantCommand()
    data class Plan(val steps: List<AssistantCommand>) : AssistantCommand()
    data object StopActions : AssistantCommand()
    data class Invalid(val reason: String) : AssistantCommand()

    /** True for commands that operate the phone through Screen Control. */
    val isDeviceAction: Boolean
        get() = this is OpenApp || this is SearchYoutube || this is SearchInApp ||
            this is TapText || this is TapFirstResult || this is Call ||
            this is WhatsAppMessage || this is SmsMessage || this is Scroll ||
            this is NavigateBack || this is TypeText || this is PressSend || this is Plan

    /** Tapping, typing, sending, calling and messaging need an explicit user confirmation. */
    val requiresConfirmation: Boolean
        get() = when (this) {
            is TypeText, is SearchInApp, is TapText, is TapFirstResult, is PressSend,
            is Call, is WhatsAppMessage, is SmsMessage -> true
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
        is Call -> "Dialer kholo: $number (call aap khud dabayenge)"
        is WhatsAppMessage -> "WhatsApp chat kholo: $number\nMessage: \"$text\"\n(Send aap khud dabayenge)"
        is SmsMessage -> "SMS kholo: $number\nMessage: \"$text\"\n(Send aap khud dabayenge)"
        is Scroll -> "Scroll " + if (direction == ScrollDirection.DOWN) "neeche" else "upar"
        is NavigateBack -> "Back jao"
        is TypeText -> "Text likho: \"$text\""
        is PressSend -> "Send dabao (message bhejo)"
        is Plan -> steps.joinToString(" -> ") { it.describe() }
        else -> ""
    }

    /** First-person Hinglish used when MAYA reads the plan aloud for confirmation. */
    fun spoken(): String = when (this) {
        is OpenApp -> "$appName kholungi"
        is SearchYoutube -> "YouTube par \"$query\" search karungi"
        is SearchInApp -> "\"$query\" search karungi"
        is TapText -> "\"$label\" dabaungi"
        is TapFirstResult -> "pehla result kholungi"
        is Call -> "$number ka dialer kholungi"
        is WhatsAppMessage -> "$number ki WhatsApp chat message ke saath kholungi"
        is SmsMessage -> "$number ka SMS message ke saath kholungi"
        is Scroll -> "scroll " + if (direction == ScrollDirection.DOWN) "neeche karungi" else "upar karungi"
        is NavigateBack -> "peeche jaungi"
        is TypeText -> "\"$text\" likhungi"
        is PressSend -> "Send dabaungi"
        is Plan -> steps.joinToString(", phir ") { it.spoken() }
        else -> ""
    }
}
