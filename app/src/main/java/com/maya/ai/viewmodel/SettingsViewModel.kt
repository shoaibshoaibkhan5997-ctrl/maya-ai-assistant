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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as MayaApp
    private val prefs = container.prefs

    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, MayaSettings())
    val apiConfigured = container.keys.configured
    val serviceConnected = MayaAccessibilityService.connected
    val tts = container.tts

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

    fun setSpeakEnabled(v: Boolean) { viewModelScope.launch { prefs.setSpeakEnabled(v) }; if (!v) tts.stop() }
    fun setSpeechRate(v: Float) { viewModelScope.launch { prefs.setSpeechRate(v) } }
    fun setVolume(v: Float) { viewModelScope.launch { prefs.setVolume(v) } }
    fun setVoice(name: String?) { viewModelScope.launch { prefs.setVoiceName(name) } }
    fun setInputLang(tag: String) { viewModelScope.launch { prefs.setInputLang(tag) } }

    fun testVoice() {
        val s = settings.value
        tts.speak("Namaste! Main MAYA hoon.", s.speechRate, s.volume, s.voiceName)
    }

    fun stopSpeaking() = tts.stop()

    fun enableScreenControl() { viewModelScope.launch { prefs.setScreenControl(true) } }
    fun disableScreenControl() = container.disableScreenControl()

    fun clearAllData() = container.clearAllData()
}
