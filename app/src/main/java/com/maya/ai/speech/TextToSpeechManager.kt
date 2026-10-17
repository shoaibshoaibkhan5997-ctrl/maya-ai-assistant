package com.maya.ai.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

data class VoiceInfo(val name: String, val label: String)

/**
 * Android TextToSpeech wrapper. It uses the best installed Hindi / Indian-English voice.
 * Which voice exists (and whether it sounds female) depends on the phone's installed TTS engine.
 */
class TextToSpeechManager(context: Context) {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _status = MutableStateFlow("TTS shuru ho raha hai...")
    val status: StateFlow<String> = _status
    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { code ->
            val t = tts
            if (code != TextToSpeech.SUCCESS || t == null) {
                _status.value = "Is phone par koi Text-to-Speech engine nahi chal raha. Android Settings mein TTS engine install/enable karein."
            } else {
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _speaking.value = true }
                    override fun onDone(utteranceId: String?) { _speaking.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _speaking.value = false }
                    override fun onError(utteranceId: String?, errorCode: Int) { _speaking.value = false }
                })
                _voices.value = loadVoices(t)
                _ready.value = true
                _status.value = if (_voices.value.isEmpty())
                    "Hindi / Indian English voice installed nahi hai. Neeche se voice data install karein."
                else "Ready. ${_voices.value.size} Indian voice(s) mili."
            }
        }
    }

    private fun candidates(t: TextToSpeech): List<Voice> = try {
        (t.voices ?: emptySet()).filter {
            val l = it.locale
            (l.language == "hi" || l.language == "en") && l.country == "IN"
        }.sortedWith(
            compareByDescending<Voice> { it.name.contains("female", true) }
                .thenBy { it.isNetworkConnectionRequired }
                .thenBy { it.name }
        )
    } catch (_: Exception) { emptyList() }

    private fun loadVoices(t: TextToSpeech) = candidates(t).map {
        VoiceInfo(it.name, "${it.locale.displayName} - ${it.name}${if (it.isNetworkConnectionRequired) " (online)" else ""}")
    }

    fun speak(text: String, rate: Float, volume: Float, preferredVoice: String?) {
        val t = tts
        if (t == null || !_ready.value) { _status.value = "TTS abhi ready nahi hai."; return }
        val clean = text.replace(Regex("[*_`#>]"), "").trim()
        if (clean.isEmpty()) return

        val hindi = clean.any { it in '\u0900'..'\u097F' }
        val locale = if (hindi) Locale("hi", "IN") else Locale("en", "IN")

        val list = candidates(t).filter { it.locale.language == locale.language }
        val voice = list.firstOrNull { it.name == preferredVoice } ?: list.firstOrNull()
        if (voice != null) {
            t.voice = voice
        } else {
            val r = t.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                _status.value = "${locale.displayName} voice data missing hai. Voice Settings se install karein."
                return
            }
        }
        t.setSpeechRate(rate.coerceIn(0.5f, 1.5f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "maya-${System.nanoTime()}")
    }

    fun stop() {
        tts?.stop()
        _speaking.value = false
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
