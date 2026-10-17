package com.maya.ai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "maya_prefs")

data class MayaSettings(
    val model: String = DEFAULT_MODEL,
    val speakEnabled: Boolean = true,
    val speechRate: Float = 1.0f,
    val volume: Float = 1.0f,
    val voiceName: String? = null,
    val inputLang: String = "hi-IN",
    val screenControlEnabled: Boolean = false, // OFF by default
    val useElevenLabs: Boolean = false,
    val elevenVoiceId: String = DEFAULT_ELEVEN_VOICE,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_ELEVEN_VOICE = "CNKl99QEbWm8RQ4D8GfC"
    }
}

class PreferencesRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object K {
        val MODEL = stringPreferencesKey("model")
        val SPEAK = booleanPreferencesKey("speak_enabled")
        val RATE = floatPreferencesKey("speech_rate")
        val VOLUME = floatPreferencesKey("volume")
        val VOICE = stringPreferencesKey("voice_name")
        val LANG = stringPreferencesKey("input_lang")
        val SCREEN = booleanPreferencesKey("screen_control")
        val USE_ELEVEN = booleanPreferencesKey("use_elevenlabs")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice_id")
    }

    val settings: Flow<MayaSettings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            MayaSettings(
                model = p[K.MODEL] ?: MayaSettings.DEFAULT_MODEL,
                speakEnabled = p[K.SPEAK] ?: true,
                speechRate = p[K.RATE] ?: 1.0f,
                volume = p[K.VOLUME] ?: 1.0f,
                voiceName = p[K.VOICE],
                inputLang = p[K.LANG] ?: "hi-IN",
                screenControlEnabled = p[K.SCREEN] ?: false,
                useElevenLabs = p[K.USE_ELEVEN] ?: false,
                elevenVoiceId = p[K.ELEVEN_VOICE] ?: MayaSettings.DEFAULT_ELEVEN_VOICE,
            )
        }

    suspend fun setModel(v: String) = store.edit { it[K.MODEL] = v }
    suspend fun setSpeakEnabled(v: Boolean) = store.edit { it[K.SPEAK] = v }
    suspend fun setSpeechRate(v: Float) = store.edit { it[K.RATE] = v }
    suspend fun setVolume(v: Float) = store.edit { it[K.VOLUME] = v }
    suspend fun setInputLang(v: String) = store.edit { it[K.LANG] = v }
    suspend fun setScreenControl(v: Boolean) = store.edit { it[K.SCREEN] = v }
    suspend fun setUseElevenLabs(v: Boolean) = store.edit { it[K.USE_ELEVEN] = v }
    suspend fun setElevenVoiceId(v: String) = store.edit { it[K.ELEVEN_VOICE] = v }
    suspend fun setVoiceName(v: String?) = store.edit {
        if (v == null) it.remove(K.VOICE) else it[K.VOICE] = v
    }

    suspend fun clearAll() {
        store.edit { it.clear() }
    }
}
