package com.maya.ai.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech from the Gemini API (text-to-speech model) using the user's own Gemini key.
 * On any failure the caller's [onFailure] runs so the phone's own voice can take over.
 */
class GeminiVoice(private val context: Context, private val keys: ApiKeyManager) {

    private class VoiceException(message: String) : Exception(message)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null

    fun speak(text: String, volume: Float, onFailure: () -> Unit) {
        stop()
        val key = keys.get()
        val clean = text.replace(Regex("[*_`#>]"), "").trim().take(MAX_CHARS)
        if (key == null || clean.isEmpty()) { onFailure(); return }

        _speaking.value = true
        _status.value = "Gemini awaaz ban rahi hai..."
        job = scope.launch {
            try {
                val file = fetch(clean, key)
                play(file, volume, onFailure)
            } catch (e: CancellationException) {
                _speaking.value = false
                throw e
            } catch (e: VoiceException) {
                fail(e.message ?: "Gemini awaaz nahi bani.", onFailure)
            } catch (e: Exception) {
                fail("Gemini awaaz play nahi ho payi.", onFailure)
            }
        }
    }

    private fun fail(reason: String, onFailure: () -> Unit) {
        _status.value = "$reason Phone ki awaaz use ho rahi hai."
        _speaking.value = false
        onFailure()
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

    private fun play(file: File, volume: Float, onFailure: () -> Unit) {
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        val v = volume.coerceIn(0f, 1f)
        mp.setVolume(v, v)
        mp.setOnPreparedListener {
            it.start()
            _status.value = "Bol rahi hai (Gemini awaaz)."
        }
        mp.setOnCompletionListener {
            releasePlayer()
            _speaking.value = false
        }
        mp.setOnErrorListener { _, _, _ ->
            releasePlayer()
            fail("Audio play nahi ho paya.", onFailure)
            true
        }
        mp.prepareAsync()
    }

    private suspend fun fetch(text: String, key: String): File = withContext(Dispatchers.IO) {
        val conn = URL("$BASE/$MODEL:generateContent").openConnection() as HttpURLConnection
        val thisJob = coroutineContext[Job]
        val handle = thisJob?.invokeOnCompletion { if (it is CancellationException) conn.disconnect() }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", "Say in a warm, friendly, cheerful voice: $text")
                ))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE)
                    ))))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw VoiceException(describe(code))

            val inline = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optJSONObject("inlineData")
                ?: throw VoiceException("Gemini ne audio nahi diya.")
            val data = inline.optString("data", "")
            if (data.isEmpty()) throw VoiceException("Gemini ne audio nahi diya.")
            val rate = Regex("rate=(\\d+)").find(inline.optString("mimeType", ""))
                ?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            val pcm = Base64.decode(data, Base64.DEFAULT)

            val out = File(context.cacheDir, "maya_gemini_tts.wav")
            out.writeBytes(wav(pcm, rate))
            out
        } catch (e: SocketTimeoutException) {
            throw VoiceException("Gemini awaaz time out ho gayi.")
        } catch (e: IOException) {
            if (thisJob?.isActive == false) throw CancellationException("cancelled")
            throw VoiceException("Network problem.")
        } finally {
            handle?.dispose()
            conn.disconnect()
        }
    }

    private fun describe(code: Int) = when (code) {
        429 -> "Gemini awaaz ki quota/limit khatam ho gayi."
        404 -> "Gemini awaaz wala model available nahi hai."
        401, 403 -> "Gemini key is awaaz ke liye allowed nahi hai."
        else -> "Gemini awaaz request fail hui (HTTP $code)."
    }

    /** Wraps raw 16-bit mono PCM in a WAV header so MediaPlayer can play it. */
    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val bb = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + pcm.size)
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1.toShort()); bb.putShort(1.toShort())
        bb.putInt(rate); bb.putInt(rate * 2)
        bb.putShort(2.toShort()); bb.putShort(16.toShort())
        bb.put("data".toByteArray()); bb.putInt(pcm.size)
        bb.put(pcm)
        return bb.array()
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MODEL = "gemini-2.5-flash-preview-tts"
        const val VOICE = "Kore"
        const val MAX_CHARS = 1500
    }
}
