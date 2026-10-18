package com.maya.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Push-to-talk wrapper over Android's SpeechRecognizer. Never listens in the background. */
class SpeechRecognitionManager(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    var onFinal: ((String) -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread, after RECORD_AUDIO has been granted. */
    fun start(languageTag: String) {
        if (!isAvailable()) {
            _state.value = State.Error("Is phone par speech recognition service nahi mili. Google app / Speech Services install karein.")
            return
        }
        destroy()
        _partial.value = ""
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { _partial.value = it }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) {
                    _state.value = State.Error("Kuch samajh nahi aaya. Dobara try karein.")
                } else {
                    _state.value = State.Idle
                    onFinal?.invoke(text)
                }
                destroy()
            }

            override fun onError(error: Int) {
                _state.value = State.Error(describe(error))
                destroy()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _state.value = State.Listening
        r.startListening(intent)
    }

    /** Stops listening; any speech heard so far is still delivered. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Stops immediately, delivers nothing and releases the microphone. */
    fun cancel() {
        try { recognizer?.cancel() } catch (_: Exception) { }
        destroy()
        _partial.value = ""
        _state.value = State.Idle
    }

    fun reportPermissionDenied() {
        _state.value = State.Error(
            "Microphone permission nahi mili. Voice input ke liye Android Settings > Apps > MAYA AI > Permissions mein Microphone allow karein. Aap text type karke bhi use kar sakte hain."
        )
    }

    fun clearError() {
        if (_state.value is State.Error) _state.value = State.Idle
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Awaaz samajh nahi aayi. Dobara try karein."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya (timeout)."
        SpeechRecognizer.ERROR_AUDIO -> "Audio record karne mein problem aayi."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition ke liye network problem. Internet check karein."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission chahiye."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy hai. Thodi der baad try karein."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Yeh bhasha is phone par supported nahi hai."
        else -> "Speech recognition error (code $error)."
    }
}
