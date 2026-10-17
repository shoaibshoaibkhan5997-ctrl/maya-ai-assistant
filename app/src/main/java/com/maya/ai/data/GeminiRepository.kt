package com.maya.ai.data

import com.maya.ai.domain.ChatTurn
import com.maya.ai.security.ApiKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext

class GeminiException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NO_KEY, INVALID_KEY, QUOTA, NETWORK, TIMEOUT, MODEL_UNAVAILABLE, BLOCKED, BAD_RESPONSE, OTHER }
}

/**
 * Talks to the official Gemini REST API (generateContent). The key is sent in the
 * x-goog-api-key header, never in the URL, and is never logged.
 * Docs: https://ai.google.dev/gemini-api/docs
 */
class GeminiRepository(
    private val keys: ApiKeyManager,
    private val prefs: PreferencesRepository,
) {
    /** Checks that the key works and the model exists, without saving anything. */
    suspend fun validateKey(apiKey: String, model: String) {
        requireValidModel(model)
        val (code, body) = http("GET", "$BASE/models/$model", apiKey, null, 15_000)
        if (code !in 200..299) throw mapError(code, body)
    }

    /** Sends the conversation and returns Gemini's raw JSON text (see [SYSTEM_PROMPT]). */
    suspend fun interpret(history: List<ChatTurn>): String {
        val key = keys.get() ?: throw GeminiException(
            GeminiException.Kind.NO_KEY,
            "Gemini API key set nahi hai. API Settings mein key daalein."
        )
        val model = prefs.settingsSnapshot().model
        requireValidModel(model)

        val contents = JSONArray()
        history.forEach {
            contents.put(
                JSONObject()
                    .put("role", if (it.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))

        val (code, resp) = http("POST", "$BASE/models/$model:generateContent", key, body.toString(), 45_000)
        if (code !in 200..299) throw mapError(code, resp)
        return extractText(resp)
    }

    private fun extractText(resp: String): String {
        try {
            val json = JSONObject(resp)
            val block = json.optJSONObject("promptFeedback")?.optString("blockReason", "").orEmpty()
            if (block.isNotEmpty()) throw GeminiException(
                GeminiException.Kind.BLOCKED, "Gemini ne is request ko block kiya ($block)."
            )
            val cand = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw GeminiException(GeminiException.Kind.BAD_RESPONSE, "Gemini se koi jawab nahi mila.")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
            if (sb.isBlank()) throw GeminiException(
                GeminiException.Kind.BAD_RESPONSE,
                "Gemini ka jawab khaali tha (${cand.optString("finishReason", "unknown")})."
            )
            return sb.toString()
        } catch (e: JSONException) {
            throw GeminiException(GeminiException.Kind.BAD_RESPONSE, "Gemini ka jawab padh nahi paaye.")
        }
    }

    private fun mapError(code: Int, body: String): GeminiException {
        val lower = body.lowercase()
        return when {
            code == 429 || "resource_exhausted" in lower -> GeminiException(
                GeminiException.Kind.QUOTA,
                "Quota/rate limit poori ho gayi. Thodi der baad try karein ya apna Gemini plan/quota check karein."
            )
            "api key not valid" in lower || "api_key_invalid" in lower || code == 401 -> GeminiException(
                GeminiException.Kind.INVALID_KEY, "API key galat hai. Key dobara check karein."
            )
            code == 403 -> GeminiException(
                GeminiException.Kind.INVALID_KEY,
                "Access mana hai (403). Key ya uski permissions/region check karein."
            )
            code == 404 -> GeminiException(
                GeminiException.Kind.MODEL_UNAVAILABLE,
                "Yeh model available nahi hai. API Settings mein dusra model naam daalein."
            )
            code in 500..599 -> GeminiException(GeminiException.Kind.OTHER, "Gemini server problem ($code). Baad mein try karein.")
            else -> GeminiException(GeminiException.Kind.OTHER, "Request fail hui (HTTP $code).")
        }
    }

    private fun requireValidModel(model: String) {
        if (!MODEL_REGEX.matches(model)) throw GeminiException(
            GeminiException.Kind.MODEL_UNAVAILABLE, "Model ka naam galat hai."
        )
    }

    private suspend fun http(method: String, url: String, key: String, body: String?, readTimeoutMs: Int): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            val job = coroutineContext[Job]
            // Cancelling the coroutine closes the socket so blocking reads stop immediately.
            val handle = job?.invokeOnCompletion { if (it is CancellationException) conn.disconnect() }
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("x-goog-api-key", key)
                conn.setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to text
            } catch (e: SocketTimeoutException) {
                throw GeminiException(GeminiException.Kind.TIMEOUT, "Request time out ho gayi. Internet check karke dobara try karein.")
            } catch (e: IOException) {
                if (job?.isActive == false) throw CancellationException("cancelled")
                throw GeminiException(GeminiException.Kind.NETWORK, "Network problem. Internet connection check karein.")
            } finally {
                handle?.dispose()
                conn.disconnect()
            }
        }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val MODEL_REGEX = Regex("[A-Za-z0-9._-]{1,80}")

        const val SYSTEM_PROMPT = """You are MAYA, a friendly, helpful AI assistant. You communicate naturally in Hindi, Hinglish, or English based on the user's preference. You are concise by default, explain technical topics step by step, and never claim to have completed an action unless its success has been confirmed.

You also classify every user message. Reply with exactly ONE JSON object and nothing else:
{"type":"...","reply":"...","app":"...","direction":"...","text":"...","unsupported":"..."}

type must be one of: ANSWER_QUESTION, CLARIFY, OPEN_APP, SCROLL, NAVIGATE_BACK, TYPE_TEXT, STOP_ACTIONS.
Rules:
- ANSWER_QUESTION for ordinary questions, chat, explanations, translations. Put the full answer in "reply", in the language the user prefers. Informational questions must NEVER become device actions.
- Use a device action type ONLY when the user explicitly asks you to operate the phone right now (for example "YouTube kholo", "neeche scroll karo", "pichhle page par jao", "ye text input box mein likho").
- OPEN_APP: "app" is the app name in Latin letters as it would appear on an Android launcher (e.g. YouTube, Settings, Chrome).
- SCROLL: "direction" is UP or DOWN.
- TYPE_TEXT: "text" is the exact text to type.
- STOP_ACTIONS: the user asks to stop, wait, or turn off screen control.
- CLARIFY: the request is ambiguous; put one short question in "reply".
- Never ask to enter passwords, make payments, purchases or change security settings. If asked, use ANSWER_QUESTION and explain you cannot do that.
- Decide the type ONLY from the LATEST user message. Earlier messages are context only. Never repeat or continue an earlier device action unless the latest message explicitly asks for it again. Greetings and chat such as "hello" or "bolo" are ANSWER_QUESTION.
- If the user asks for several things at once, return the FIRST supported device action as "type" and put the remaining parts that this app cannot do (anything other than the listed types, such as searching inside an app, tapping buttons, playing videos, sending messages) in "unsupported", briefly in the user's language. Otherwise leave "unsupported" empty.
- If the whole request needs only actions this app cannot do, use ANSWER_QUESTION and honestly say you cannot do it yet.
- You do not know the phone's current state. Never say an action is done; the app reports results itself."""
    }
}

suspend fun PreferencesRepository.settingsSnapshot(): MayaSettings = settings.first()
