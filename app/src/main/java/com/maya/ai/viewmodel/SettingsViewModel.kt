package com.maya.ai.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maya.ai.MayaApp
import com.maya.ai.data.GeminiException
import com.maya.ai.data.MayaSettings
import com.maya.ai.service.MayaAccessibilityService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as MayaApp
    private val prefs = container.prefs

    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, MayaSettings())
    val apiConfigured = container.keys.configured
    val elevenConfigured = container.elevenKeys.configured
    val serviceConnected = MayaAccessibilityService.connected
    val tts = container.tts
    val cloud = container.cloudVoice

    val speaking = combine(tts.speaking, cloud.speaking) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    var busy by mutableStateOf(false); private set
    var apiMessage by mutableStateOf<String?>(null); private set
    var apiMessageIsError by mutableStateOf(false); private set

    /** Validates against Gemini first; only a working key is stored (encrypted). */
    fun saveKey(rawKey: String, rawModel: String) {
        val key = rawKey.trim()
        val model = rawModel.trim().ifEmpty { MayaSettings.DEFAULT_MODEL }
        if (key.isEmpty()) { setApiMsg("API key khaali hai.", true); return }
        if (busy) return
        busy = true
        setApiMsg("Key check ho rahi hai...", false)
        viewModelScope.launch {
            try {
                container.gemini.validateKey(key, model)
                container.keys.save(key)
                prefs.setModel(model)
                setApiMsg("Key valid hai aur secure tarike se save ho gayi.", false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiException) {
                setApiMsg(e.message ?: "Key validate nahi ho payi.", true)
            } catch (e: Exception) {
                setApiMsg("Key save nahi ho payi.", true)
            } finally {
                busy = false
            }
        }
    }

    fun clearKey() {
        container.keys.clear()
        setApiMsg("API key hata di gayi.", false)
    }

    private fun setApiMsg(msg: String, error: Boolean) { apiMessage = msg; apiMessageIsError = error }

    fun setSpeakEnabled(v: Boolean) { viewModelScope.launch { prefs.setSpeakEnabled(v) }; if (!v) stopSpeaking() }
    fun setSpeechRate(v: Float) { viewModelScope.launch { prefs.setSpeechRate(v) } }
    fun setVolume(v: Float) { viewModelScope.launch { prefs.setVolume(v) } }
    fun setVoice(name: String?) { viewModelScope.launch { prefs.setVoiceName(name) } }
    fun setInputLang(tag: String) { viewModelScope.launch { prefs.setInputLang(tag) } }

    // ElevenLabs (optional cloud voice)
    fun setUseEleven(v: Boolean) { viewModelScope.launch { prefs.setUseElevenLabs(v) } }

    fun saveElevenSettings(rawKey: String, rawVoiceId: String) {
        val id = rawVoiceId.trim()
        if (!Regex("[A-Za-z0-9]{10,40}").matches(id)) {
            cloud.setStatus("Voice ID ka format galat hai.")
            return
        }
        if (rawKey.isNotBlank()) container.elevenKeys.save(rawKey.trim())
        viewModelScope.launch { prefs.setElevenVoiceId(id) }
        cloud.setStatus("Save ho gaya. Ab switch ON karke Test voice dabayein.")
    }

    fun clearElevenKey() {
        cloud.stop()
        container.elevenKeys.clear()
        viewModelScope.launch { prefs.setUseElevenLabs(false) }
        cloud.setStatus("ElevenLabs key hata di gayi.")
    }

    fun testVoice() {
        val s = settings.value
        val text = "Namaste! Main MAYA hoon."
        if (s.useElevenLabs && container.elevenKeys.configured.value) {
            tts.stop()
            cloud.speak(text, s.elevenVoiceId, s.volume)
        } else {
            cloud.stop()
            tts.speak(text, s.speechRate, s.volume, s.voiceName)
        }
    }

    fun stopSpeaking() { tts.stop(); cloud.stop() }

    fun enableScreenControl() { viewModelScope.launch { prefs.setScreenControl(true) } }
    fun disableScreenControl() = container.disableScreenControl()

    fun clearAllData() = container.clearAllData()
}
