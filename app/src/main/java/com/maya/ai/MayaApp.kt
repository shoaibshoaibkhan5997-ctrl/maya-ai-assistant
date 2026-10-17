package com.maya.ai

import android.app.Application
import com.maya.ai.data.GeminiRepository
import com.maya.ai.data.PreferencesRepository
import com.maya.ai.security.ApiKeyManager
import com.maya.ai.service.MayaAccessibilityService
import com.maya.ai.speech.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MayaApp : Application() {
    lateinit var keys: ApiKeyManager; private set
    lateinit var prefs: PreferencesRepository; private set
    lateinit var gemini: GeminiRepository; private set
    lateinit var tts: TextToSpeechManager; private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        keys = ApiKeyManager(this)
        prefs = PreferencesRepository(this)
        gemini = GeminiRepository(keys, prefs)
        tts = TextToSpeechManager(this)

        // The service honours the in-app switch.
        appScope.launch {
            prefs.settings.collect { MayaAccessibilityService.acceptingCommands = it.screenControlEnabled }
        }
        // If the user revokes the Accessibility permission in Android, switch MAYA's side OFF too.
        appScope.launch {
            var wasConnected = false
            MayaAccessibilityService.connected.collect { now ->
                if (wasConnected && !now) prefs.setScreenControl(false)
                wasConnected = now
            }
        }
    }

    /** Stops accepting commands and asks Android to disable the service where possible. */
    fun disableScreenControl() {
        MayaAccessibilityService.acceptingCommands = false
        appScope.launch { prefs.setScreenControl(false) }
        MayaAccessibilityService.instance?.disableSelf()
    }

    fun clearAllData() {
        disableScreenControl()
        tts.stop()
        keys.clear()
        appScope.launch { prefs.clearAll() }
    }
}
