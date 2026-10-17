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
    data class Scroll(val direction: ScrollDirection) : AssistantCommand()
    data object NavigateBack : AssistantCommand()
    data class TypeText(val text: String) : AssistantCommand()
    data object StopActions : AssistantCommand()
    data class Invalid(val reason: String) : AssistantCommand()

    /** True for commands that operate the phone through Screen Control. */
    val isDeviceAction: Boolean
        get() = this is OpenApp || this is Scroll || this is NavigateBack || this is TypeText

    /** Consequential commands need an explicit user tap before running. */
    val requiresConfirmation: Boolean
        get() = this is TypeText
}
