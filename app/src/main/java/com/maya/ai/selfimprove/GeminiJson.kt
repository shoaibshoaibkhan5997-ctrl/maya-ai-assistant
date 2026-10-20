package com.maya.ai.selfimprove

import com.maya.ai.data.PreferencesRepository
import com.maya.ai.data.settingsSnapshot
import com.maya.ai.security.ApiKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * One-shot JSON call to the same Gemini key/model as the rest of MAYA, but without MAYA's chat
 * personality. The key is sent in a header and never logged.
 */
class GeminiJson(private val keys: ApiKeyManager, private val prefs: PreferencesRepository) {

    suspend fun generate(system: String, user: String): String {
        val key = keys.get() ?: throw SelfImproveException("Gemini API key set nahi hai.")
        val model = prefs.settingsSnapshot().model
        if (!Regex("[A-Za-z0-9._-]{1,80}").matches(model)) throw SelfImproveException("Gemini model ka naam galat hai.")

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                ),
            )
            .put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json")
                    .put("temperature", 0.2).put("maxOutputTokens", 16384),
            )

        val (code, resp) = post("$BASE/models/$model:generateContent", key, body.toString())
        if (code !in 200..299) throw SelfImproveException(describe(code))
        return extract(resp)
    }

    private fun extract(resp: String): String {
        val json = try { JSONObject(resp) } catch (e: JSONException) {
            throw SelfImproveException("Gemini ka jawab padh nahi paaye.")
        }
        val block = json.optJSONObject("promptFeedback")?.optString("blockReason", "").orEmpty()
        if (block.isNotEmpty()) throw SelfImproveException("Gemini ne request block ki ($block).")
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw SelfImproveException("Gemini se koi jawab nahi mila.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
        if (sb.isBlank()) throw SelfImproveException("Gemini ka jawab khaali tha.")
        return sb.toString()
    }

    private fun describe(code: Int) = when (code) {
        429 -> "Gemini ki quota/limit khatam ho gayi. Thodi der baad try karein."
        401, 403 -> "Gemini key is request ke liye allowed nahi hai."
        404 -> "Gemini model available nahi hai."
        in 500..599 -> "Gemini server problem ($code)."
        else -> "Gemini request fail hui (HTTP $code)."
    }

    private suspend fun post(url: String, key: String, body: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            val job = coroutineContext[Job]
            val handle = job?.invokeOnCompletion { if (it is CancellationException) conn.disconnect() }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("x-goog-api-key", key)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } catch (e: SocketTimeoutException) {
                throw SelfImproveException("Gemini time out ho gaya.")
            } catch (e: IOException) {
                if (job?.isActive == false) throw CancellationException("cancelled")
                throw SelfImproveException("Network problem.")
            } finally {
                handle?.dispose()
                conn.disconnect()
            }
        }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}
