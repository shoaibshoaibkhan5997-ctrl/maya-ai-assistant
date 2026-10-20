package com.maya.ai.overlay

import kotlinx.coroutines.suspendCancellableCoroutine
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Listens for "Hey Maya" with Vosk, fully offline. [awaitWake] owns the microphone only while
 * it is suspended; it always releases the microphone before it returns or is cancelled.
 */
class WakeWordDetector(private val model: Model) {

    sealed interface Result {
        data object Detected : Result
        data class Failed(val message: String) : Result
    }

    suspend fun awaitWake(): Result = suspendCancellableCoroutine { cont ->
        val closed = AtomicBoolean(false)
        var recognizer: Recognizer? = null
        var service: SpeechService? = null

        fun release() {
            if (!closed.compareAndSet(false, true)) return
            try { service?.stop() } catch (_: Exception) { }
            try { service?.shutdown() } catch (_: Exception) { }
            try { recognizer?.close() } catch (_: Exception) { }
        }

        fun finish(r: Result) {
            release()
            if (cont.isActive) cont.resume(r)
        }

        cont.invokeOnCancellation { release() }

        try {
            val rec = Recognizer(model, SAMPLE_RATE, WakeWordMatcher.GRAMMAR)
            recognizer = rec
            val svc = SpeechService(rec, SAMPLE_RATE)
            service = svc
            val started = svc.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) { }

                override fun onResult(hypothesis: String?) {
                    if (WakeWordMatcher.matches(hypothesis)) finish(Result.Detected)
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (WakeWordMatcher.matches(hypothesis)) finish(Result.Detected)
                }

                override fun onError(exception: Exception?) {
                    finish(Result.Failed(exception?.message ?: "Wake-word detector mein error aaya."))
                }

                override fun onTimeout() {
                    finish(Result.Failed("Wake-word detector time out ho gaya."))
                }
            })
            if (!started) {
                finish(Result.Failed("Mic shuru nahi ho paya (kisi aur app ne mic le rakha ho sakta hai)."))
            }
        } catch (e: Exception) {
            finish(Result.Failed(e.message ?: "Wake-word detector shuru nahi ho paya."))
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0f
    }
}
