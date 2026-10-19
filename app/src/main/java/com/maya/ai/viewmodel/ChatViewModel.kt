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
import com.maya.ai.launcher.AppLauncher
import com.maya.ai.service.MayaAccessibilityService
import com.maya.ai.speech.SpeechRecognitionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val cloud = container.cloudVoice
    val speech = SpeechRecognitionManager(app)
    val apiConfigured = container.keys.configured
    val settings = container.prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, MayaSettings())

    /** True while any voice (Gemini, ElevenLabs or the phone's own) is speaking. */
    val speaking = container.speaking

    val messages = mutableStateListOf<ChatMessage>()
    var input by mutableStateOf("")
    var loading by mutableStateOf(false); private set
    /** Steps waiting for the user's "Haan, chalao". */
    var pendingPlan by mutableStateOf<List<AssistantCommand>?>(null); private set
    /** True while a device-action plan is running (shows the STOP button). */
    var running by mutableStateOf(false); private set
    var lastMayaText by mutableStateOf<String?>(null); private set

    /** Continuous voice conversation: listen -> process -> respond -> listen again. */
    var voiceMode by mutableStateOf(false); private set
    var voiceStatus by mutableStateOf(""); private set

    private var job: Job? = null
    private var planJob: Job? = null
    private var voiceJob: Job? = null

    init {
        speech.onFinal = { text ->
            input = text
            if (voiceMode) send()   // in Voice Mode the heard sentence is sent automatically
        }
        viewModelScope.launch {
            speech.partial.collect { if (it.isNotEmpty() && speech.state.value is SpeechRecognitionManager.State.Listening) input = it }
        }
    }

    // ---------- continuous voice mode ----------

    fun startVoiceMode() {
        if (voiceMode) return                       // never two sessions / two microphones
        if (!container.keys.configured.value) {
            messages.add(ChatMessage(Role.STATUS, "Gemini API key set nahi hai. Menu > Gemini API mein key daalein.", ok = false))
            return
        }
        voiceMode = true
        stopSpeaking()
        speech.cancel()
        speech.clearError()
        voiceJob = viewModelScope.launch { runVoiceLoop() }
    }

    /** Ends the session and releases the microphone and audio. */
    fun stopVoiceMode(note: String? = null, ok: Boolean = true) {
        if (!voiceMode) return
        voiceMode = false
        voiceJob?.cancel()
        voiceJob = null
        speech.cancel()
        stopSpeaking()
        if (loading) cancelGeneration()
        voiceStatus = ""
        if (note != null) messages.add(ChatMessage(Role.STATUS, note, ok))
    }

    /** Android does not allow a background app to keep the microphone, so the session ends. */
    fun onAppBackground() {
        if (voiceMode) {
            stopVoiceMode("App background mein gaya, isliye Voice Mode aur mic band kar diye gaye. Dobara shuru karne ke liye Voice Mode dabayein.", ok = false)
        }
    }

    private suspend fun runVoiceLoop() {
        var failedRounds = 0
        while (voiceMode) {
            if (!waitUntilIdle()) return

            voiceStatus = "Sun rahi hoon... boliye"
            speech.start(settings.value.inputLang)

            var waited = 0
            while (voiceMode && speech.state.value is SpeechRecognitionManager.State.Listening) {
                delay(100)
                waited += 100
                if (waited == MAX_LISTEN_MS) speech.stop()
                if (waited > MAX_LISTEN_MS + 5_000) { speech.cancel(); break }
            }
            if (!voiceMode) return

            val st = speech.state.value
            if (st is SpeechRecognitionManager.State.Error) {
                failedRounds++
                if (failedRounds >= MAX_FAILED_ROUNDS) {
                    stopVoiceMode("Voice Mode band kiya gaya (mic band): ${st.message}", ok = false)
                    return
                }
                delay(500)
            } else {
                failedRounds = 0
            }
        }
    }

    /** Waits until MAYA is done thinking/speaking/acting, so the mic never hears MAYA herself. */
    private suspend fun waitUntilIdle(): Boolean {
        var quietMs = 0
        while (voiceMode) {
            val talking = speaking.value
            val busy = loading || running || pendingPlan != null
            voiceStatus = when {
                loading -> "MAYA soch rahi hai..."
                talking -> "MAYA bol rahi hai..."
                running -> "MAYA phone chala rahi hai..."
                pendingPlan != null -> "Neeche confirm karein..."
                else -> "Taiyaar..."
            }
            if (busy || talking) quietMs = 0 else quietMs += 100
            if (quietMs >= QUIET_MS) return true
            delay(100)
        }
        return false
    }

    // ---------- chat ----------

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || loading) return

        // Plain "open <app>" commands (YouTube, WhatsApp, Instagram, Chrome, Settings...) are
        // handled right here on the phone. Anything more complex still goes to Gemini below.
        val launched = AppLauncher.handle(container, text)
        if (launched != null) {
            speech.clearError()
            stopSpeaking()
            pendingPlan = null
            messages.add(ChatMessage(Role.USER, text))
            input = ""
            messages.add(ChatMessage(Role.STATUS, launched.message, launched.success))
            if (!launched.success && settings.value.speakEnabled) speak(launched.message)
            return
        }

        if (!container.keys.configured.value) {
            messages.add(ChatMessage(Role.STATUS, "Gemini API key set nahi hai. Menu > Gemini API mein key daalein.", ok = false))
            return
        }
        speech.clearError()
        if (voiceMode && speech.state.value is SpeechRecognitionManager.State.Listening) speech.cancel()
        stopSpeaking()
        pendingPlan = null
        messages.add(ChatMessage(Role.USER, text))
        input = ""
        loading = true
        job = viewModelScope.launch {
            try {
                val raw = gemini.interpret(buildHistory())
                val cmd = CommandParser.parse(raw)
                handle(cmd)
                val extra = CommandParser.unsupportedNote(raw)
                if (cmd.isDeviceAction && extra.isNotEmpty()) {
                    messages.add(ChatMessage(Role.STATUS, "Baaki hissa abhi supported nahi hai: $extra", ok = false))
                }
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
        messages.map {
            when (it.role) {
                Role.USER -> ChatTurn(fromUser = true, text = it.text)
                Role.MAYA -> ChatTurn(fromUser = false, text = it.text)
                Role.STATUS -> ChatTurn(fromUser = false, text = "(Note from app, not a request: ${it.text})")
            }
        }
            .takeLast(MAX_HISTORY)
            .dropWhile { !it.fromUser }

    private fun handle(cmd: AssistantCommand) {
        when (cmd) {
            is AssistantCommand.AnswerQuestion -> addMaya(cmd.reply)
            is AssistantCommand.Clarify -> addMaya(cmd.question)
            is AssistantCommand.Invalid ->
                messages.add(ChatMessage(Role.STATUS, cmd.reason, ok = false))
            AssistantCommand.StopActions -> {
                planJob?.cancel()
                running = false
                pendingPlan = null
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
        val steps = if (cmd is AssistantCommand.Plan) cmd.steps else listOf(cmd)
        if (cmd.requiresConfirmation) {
            pendingPlan = steps
            addMaya("Main yeh steps chalana chahti hoon. Neeche confirm karein.")
            return
        }
        runSteps(steps)
    }

    fun confirmPending() {
        val plan = pendingPlan ?: return
        pendingPlan = null
        runSteps(plan)
    }

    fun cancelPending() {
        if (pendingPlan != null) {
            pendingPlan = null
            messages.add(ChatMessage(Role.STATUS, "Cancel kiya. Kuch nahi chalaya gaya.", ok = false))
        }
    }

    fun stopPlan() {
        if (running) {
            planJob?.cancel()
            running = false
            messages.add(ChatMessage(Role.STATUS, "Aapne plan roka. Aage ke steps nahi chalenge.", ok = false))
        }
    }

    private fun runSteps(steps: List<AssistantCommand>) {
        planJob?.cancel()
        running = true
        planJob = viewModelScope.launch {
            try {
                for ((i, step) in steps.withIndex()) {
                    val svc = MayaAccessibilityService.instance
                    if (svc == null || !MayaAccessibilityService.acceptingCommands) {
                        messages.add(ChatMessage(Role.STATUS, "Screen Control band ho gaya, plan ruk gaya.", ok = false))
                        break
                    }
                    val result = runWithRetry(svc, step)
                    val prefix = if (steps.size > 1) "Step ${i + 1}: " else ""
                    messages.add(ChatMessage(Role.STATUS, prefix + result.message, result.success))
                    if (!result.success) {
                        if (steps.size > 1) messages.add(ChatMessage(Role.STATUS, "Plan yahin ruk gaya.", ok = false))
                        break
                    }
                    if (i < steps.lastIndex) delay(settleMs(step))
                }
            } finally {
                running = false
            }
        }
    }

    /** Screens load slowly, so finding a button / search box is retried a few times. */
    private suspend fun runWithRetry(svc: MayaAccessibilityService, step: AssistantCommand): ActionResult {
        val retry = step is AssistantCommand.SearchInApp || step is AssistantCommand.TapText ||
            step is AssistantCommand.TapFirstResult || step is AssistantCommand.TypeText
        var result = svc.execute(step)
        var tries = 1
        while (retry && !result.success && tries < 4) {
            delay(1000)
            result = svc.execute(step)
            tries++
        }
        return result
    }

    private fun settleMs(step: AssistantCommand): Long = when (step) {
        is AssistantCommand.OpenApp, is AssistantCommand.SearchYoutube -> 3000L
        is AssistantCommand.SearchInApp -> 2500L
        is AssistantCommand.TapText, is AssistantCommand.TapFirstResult -> 1500L
        else -> 800L
    }

    private fun addMaya(text: String) {
        messages.add(ChatMessage(Role.MAYA, text))
        lastMayaText = text
        if (settings.value.speakEnabled) speak(text)
    }

    fun speak(text: String) = container.speakText(text, settings.value)

    fun replay() { lastMayaText?.let(::speak) }
    fun stopSpeaking() = container.stopSpeech()

    fun startListening() {
        if (voiceMode) return                       // the voice loop already owns the microphone
        stopSpeaking()
        speech.start(settings.value.inputLang)
    }
    fun stopListening() = speech.stop()
    fun onMicDenied() = speech.reportPermissionDenied()

    fun clearChat() {
        job?.cancel()
        planJob?.cancel()
        running = false
        stopSpeaking()
        messages.clear()
        pendingPlan = null
        lastMayaText = null
        loading = false
    }

    override fun onCleared() {
        voiceMode = false
        speech.cancel()
        speech.destroy()
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY = 12
        const val MAX_LISTEN_MS = 60_000
        const val MAX_FAILED_ROUNDS = 3
        const val QUIET_MS = 1_000
    }
}
