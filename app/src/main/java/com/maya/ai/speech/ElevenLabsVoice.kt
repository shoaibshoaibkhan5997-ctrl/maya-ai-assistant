package com.maya.ai.speech

import android.content.Context
import android.media.MediaPlayer
import com.maya.ai.security.ApiKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Optional cloud voice using the ElevenLabs text-to-speech API.
 * The reply text is sent to ElevenLabs only while this is switched ON and a key is saved.
 */
class ElevenLabsVoice(private val context: Context, private val keys: ApiKeyManager) {

    private class CloudException(message: String) : Exception(message)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null

    fun setStatus(message: String) { _status.value = message }

    fun speak(text: String, voiceId: String, volume: Float) {
        stop()
        val key = keys.get()
        if (key == null) { _status.value = "ElevenLabs API key set nahi hai."; return }
        if (!VOICE_ID_REGEX.matches(voiceId)) { _status.value = "Voice ID ka format galat hai."; return }
        val clean = text.replace(Regex("[*_`#>]"), "").trim().take(MAX_CHARS)
        if (clean.isEmpty()) return

        _speaking.value = true
        _status.value = "Awaaz ban rahi hai..."
        job = scope.launch {
            try {
                val file = fetch(clean, voiceId, key)
                play(file, volume)
            } catch (e: CancellationException) {
                _speaking.value = false
                throw e
            } catch (e: CloudException) {
                _status.value = e.message ?: "ElevenLabs error."
                _speaking.value = false
            } catch (e: Exception) {
                _status.value = "Awaaz play nahi ho payi."
                _speaking.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { player?.stop() } catch (_: IllegalStateException) { }
        releasePlayer()
        _speaking.value = false
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun play(file: File, volume: Float) {
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        val v = volume.coerceIn(0f, 1f)
        mp.setVolume(v, v)
        mp.setOnPreparedListener {
            it.start()
            _status.value = "Bol rahi hai (ElevenLabs)."
        }
        mp.setOnCompletionListener {
            releasePlayer()
            _speaking.value = false
        }
        mp.setOnErrorListener { _, _, _ ->
            releasePlayer()
            _speaking.value = false
            _status.value = "Audio play nahi ho paya."
            true
        }
        mp.prepareAsync()
    }

    private suspend fun fetch(text: String, voiceId: String, key: String): File =
        withContext(Dispatchers.IO) {
            val conn = URL("$BASE/$voiceId?output_format=mp3_44100_128").openConnection() as HttpURLConnection
            val thisJob = coroutineContext[Job]
            val handle = thisJob?.invokeOnCompletion { if (it is CancellationException) conn.disconnect() }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("xi-api-key", key)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "audio/mpeg")
                conn.doOutput = true
                val body = JSONObject().put("text", text).put("model_id", MODEL).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw CloudException(describe(code, err))
                }
                val out = File(context.cacheDir, "maya_tts.mp3")
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                out
            } catch (e: SocketTimeoutException) {
                throw CloudException("ElevenLabs time out ho gaya. Internet check karein.")
            } catch (e: IOException) {
                if (thisJob?.isActive == false) throw CancellationException("cancelled")
                throw CloudException("Network problem. Internet check karein.")
            } finally {
                handle?.dispose()
                conn.disconnect()
            }
        }

    private fun describe(code: Int, body: String): String {
        val lower = body.lowercase()
        return when {
            code == 401 -> "ElevenLabs API key galat hai."
            code == 402 || "paid_plan" in lower || "payment" in lower ->
                "Yeh voice/feature aapke ElevenLabs plan mein API se available nahi hai (paid plan chahiye ho sakta hai)."
            code == 404 || "voice_not_found" in lower -> "Voice ID nahi mili. ID check karein."
            code == 422 -> "Request galat hai (voice ID ya text check karein)."
            code == 429 -> "ElevenLabs quota/rate limit poori ho gayi."
            else -> "ElevenLabs request fail hui (HTTP $code)."
        }
    }

    private companion object {
        const val BASE = "https://api.elevenlabs.io/v1/text-to-speech"
        const val MODEL = "eleven_multilingual_v2"
        const val MAX_CHARS = 2000
        val VOICE_ID_REGEX = Regex("[A-Za-z0-9]{10,40}")
    }
}
