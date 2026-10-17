package com.maya.ai.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maya.ai.MayaApp
import com.maya.ai.data.GeminiException
import com.maya.ai.data.MayaSettings
import com.maya.ai.domain.ActionResult
import com.maya.ai.domain.AssistantCommand
import com.maya.ai.domain.ChatTurn
import com.maya.ai.domain.CommandParser
import com.maya.ai.service.MayaAccessibilityService
import com.maya.ai.speech.SpeechRecognitionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

enum class Role { USER, MAYA, STATUS }

/** STATUS messages are real device-action results; MAYA messages are AI text only. */
data class ChatMessage(val role: Role, val text: String, val ok: Boolean = true)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as MayaApp
    private val gemini = container.gemini
    val tts = container.tts
    val speech = SpeechRecognitionManager(app)
    val apiConfigured = container.keys.configured
    val settings = container.prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, MayaSettings())

    val messages = mutableStateListOf<ChatMessage>()
    var input by mutableStateOf("")
    var loading by mutableStateOf(false); private set
    var pendingType by mutableStateOf<AssistantCommand.TypeText?>(null); private set
    var lastMayaText by mutableStateOf<String?>(null); private set

    private var job: Job? = null

    init {
        speech.onFinal = { input = it }
        viewModelScope.launch {
            speech.partial.collect { if (it.isNotEmpty() && speech.state.value is SpeechRecognitionManager.State.Listening) input = it }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || loading) return
        if (!container.keys.configured.value) {
            messages.add(ChatMessage(Role.STATUS, "Gemini API key set nahi hai. Menu > Gemini API mein key daalein.", ok = false))
            return
        }
        speech.clearError()
        tts.stop()
        pendingType = null
        messages.add(ChatMessage(Role.USER, text))
        input = ""
        loading = true
        job = viewModelScope.launch {
            try {
                val raw = gemini.interpret(buildHistory())
                handle(CommandParser.parse(raw))
            } catch (e: CancellationException) {
                messages.add(ChatMessage(Role.STATUS, "Request cancel ki gayi.", ok = false))
                throw e
            } catch (e: GeminiException) {
                messages.add(ChatMessage(Role.STATUS, e.message ?: "Unknown error", ok = false))
            } finally {
                loading = false
            }
        }
    }

    fun cancelGeneration() { job?.cancel() }

    private fun buildHistory(): List<ChatTurn> =
        messages.filter { it.role != Role.STATUS }
            .map { ChatTurn(fromUser = it.role == Role.USER, text = it.text) }
            .takeLast(MAX_HISTORY)
            .dropWhile { !it.fromUser }

    private fun handle(cmd: AssistantCommand) {
        when (cmd) {
            is AssistantCommand.AnswerQuestion -> addMaya(cmd.reply)
            is AssistantCommand.Clarify -> addMaya(cmd.question)
            is AssistantCommand.Invalid ->
                messages.add(ChatMessage(Role.STATUS, cmd.reason, ok = false))
            AssistantCommand.StopActions -> {
                pendingType = null
                container.disableScreenControl()
                messages.add(ChatMessage(Role.STATUS, "Pending actions cancel kar diye aur Screen Control band kar diya. Agar Android settings mein abhi bhi ON dikhe to wahan se OFF karein."))
            }
            else -> handleDeviceAction(cmd)
        }
    }

    private fun handleDeviceAction(cmd: AssistantCommand) {
        val svcUp = MayaAccessibilityService.connected.value
        if (!settings.value.screenControlEnabled || !svcUp) {
            messages.add(ChatMessage(Role.STATUS, "Screen Control OFF hai, isliye yeh action nahi chalaya. Menu > Screen Control se ON karein.", ok = false))
            return
        }
        if (cmd is AssistantCommand.TypeText && cmd.requiresConfirmation) {
            pendingType = cmd
            addMaya("Kya main focused text box mein yeh likhoon?\n\n\"${cmd.text}\"")
            return
        }
        execute(cmd)
    }

    fun confirmPending() {
        val cmd = pendingType ?: return
        pendingType = null
        execute(cmd)
    }

    fun cancelPending() {
        if (pendingType != null) {
            pendingType = null
            messages.add(ChatMessage(Role.STATUS, "Action cancel kiya. Kuch nahi likha gaya.", ok = false))
        }
    }

    private fun execute(cmd: AssistantCommand) {
        val svc = MayaAccessibilityService.instance
        val result = svc?.execute(cmd) ?: ActionResult(false, "Accessibility service connected nahi hai.")
        messages.add(ChatMessage(Role.STATUS, result.message, result.success))
    }

    private fun addMaya(text: String) {
        messages.add(ChatMessage(Role.MAYA, text))
        lastMayaText = text
        if (settings.value.speakEnabled) speak(text)
    }

    fun speak(text: String) {
        val s = settings.value
        tts.speak(text, s.speechRate, s.volume, s.voiceName)
    }

    fun replay() { lastMayaText?.let(::speak) }
    fun stopSpeaking() = tts.stop()

    fun startListening() {
        tts.stop()
        speech.start(settings.value.inputLang)
    }
    fun stopListening() = speech.stop()
    fun onMicDenied() = speech.reportPermissionDenied()

    fun clearChat() {
        job?.cancel()
        tts.stop()
        messages.clear()
        pendingType = null
        lastMayaText = null
        loading = false
    }

    override fun onCleared() {
        speech.destroy()
        super.onCleared()
    }

    private companion object { const val MAX_HISTORY = 12 }
}
