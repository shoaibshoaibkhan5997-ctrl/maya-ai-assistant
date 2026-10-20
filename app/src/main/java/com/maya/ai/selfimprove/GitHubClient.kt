package com.maya.ai.selfimprove

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Minimal GitHub REST client for ONE repository. Reads work without a token on public repos.
 * Writes need the user's fine-grained token and only ever go to branches named maya/...
 * The token is read through [tokenProvider], sent only to GitHub, and never logged.
 */
class GitHubClient(private val tokenProvider: () -> String?) {

    data class TreeEntry(val path: String, val size: Long)
    data class FileContent(val text: String, val sha: String)
    data class RunInfo(val status: String, val conclusion: String?, val url: String)

    suspend fun defaultBranch(owner: String, repo: String): String =
        api("GET", "/repos/$owner/$repo").need("default_branch")

    /** True/false if GitHub says whether this token may push, null if unknown. */
    suspend fun canPush(owner: String, repo: String): Boolean? =
        api("GET", "/repos/$owner/$repo").optJSONObject("permissions")?.optBoolean("push", false)

    suspend fun listTree(owner: String, repo: String, branch: String): List<TreeEntry> {
        val j = api("GET", "/repos/$owner/$repo/git/trees/${encPath(branch)}?recursive=1")
        if (j.optBoolean("truncated", false)) throw GitHubException("Repo bahut bada hai (file list adhuri aayi).")
        val arr = j.optJSONArray("tree") ?: return emptyList()
        val out = ArrayList<TreeEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "blob") out.add(TreeEntry(o.optString("path"), o.optLong("size", 0)))
        }
        return out
    }

    /** Fast plain-text read used for analysis (not limited like the API). */
    suspend fun rawFile(owner: String, repo: String, ref: String, path: String): String {
        val url = "https://raw.githubusercontent.com/$owner/$repo/${encPath(ref)}/${encPath(path)}"
        val (code, text) = send(url, "GET", null, "text/plain")
        if (code == 404) throw GitHubException("File nahi mili ya access nahi hai: $path")
        if (code !in 200..299) throw GitHubException(describe(code, text))
        return text
    }

    /** Text + sha through the contents API (needed for committing). */
    suspend fun readFile(owner: String, repo: String, path: String, ref: String): FileContent {
        val j = api("GET", "/repos/$owner/$repo/contents/${encPath(path)}?ref=${Uri.encode(ref)}")
        if (j.optString("encoding") != "base64") throw GitHubException("File padh nahi paaye: $path")
        val text = String(Base64.decode(j.need("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
        return FileContent(text, j.need("sha"))
    }

    suspend fun branchSha(owner: String, repo: String, branch: String): String =
        api("GET", "/repos/$owner/$repo/git/ref/heads/${encPath(branch)}")
            .optJSONObject("object")?.need("sha") ?: throw GitHubException("Branch ka sha nahi mila.")

    suspend fun createBranch(owner: String, repo: String, name: String, fromSha: String) {
        if (!name.startsWith("maya/")) throw GitHubException("Sirf maya/ branches banai ja sakti hain.")
        api("POST", "/repos/$owner/$repo/git/refs", JSONObject().put("ref", "refs/heads/$name").put("sha", fromSha).toString())
    }

    /** [sha] is null for a brand-new file. Refuses any branch that does not start with maya/. */
    suspend fun commitFile(owner: String, repo: String, branch: String, path: String, text: String, sha: String?, message: String) {
        if (!branch.startsWith("maya/")) throw GitHubException("Sirf maya/ branches par commit hota hai.")
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        api("PUT", "/repos/$owner/$repo/contents/${encPath(path)}", body.toString())
    }

    suspend fun createPullRequest(owner: String, repo: String, head: String, base: String, title: String, body: String): String {
        val j = api(
            "POST", "/repos/$owner/$repo/pulls",
            JSONObject().put("title", title).put("head", head).put("base", base).put("body", body).toString(),
        )
        return j.need("html_url")
    }

    suspend fun latestRun(owner: String, repo: String, branch: String): RunInfo? {
        val j = api("GET", "/repos/$owner/$repo/actions/runs?branch=${Uri.encode(branch)}&per_page=1")
        val arr = j.optJSONArray("workflow_runs") ?: return null
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        return RunInfo(
            r.optString("status"),
            if (r.isNull("conclusion")) null else r.optString("conclusion"),
            r.optString("html_url"),
        )
    }

    // ---------- plumbing ----------

    private fun JSONObject.need(key: String): String =
        optString(key).takeIf { it.isNotEmpty() } ?: throw GitHubException("GitHub ke jawab mein '$key' nahi mila.")

    private fun encPath(p: String) = p.split("/").joinToString("/") { Uri.encode(it) }

    private suspend fun api(method: String, path: String, body: String? = null): JSONObject {
        val (code, text) = send("https://api.github.com$path", method, body, "application/vnd.github+json")
        if (code !in 200..299) throw GitHubException(describe(code, text))
        return try { JSONObject(if (text.isBlank()) "{}" else text) } catch (e: JSONException) {
            throw GitHubException("GitHub ka jawab samajh nahi aaya.")
        }
    }

    private fun describe(code: Int, body: String): String {
        val lower = body.lowercase()
        val msg = try { JSONObject(body).optString("message") } catch (e: JSONException) { "" }
        return when {
            (code == 403 || code == 429) && "rate limit" in lower ->
                "GitHub rate limit lag gayi. Token daalein ya thodi der baad try karein."
            code == 401 -> "GitHub token galat ya expire ho gaya hai."
            code == 403 -> "GitHub ne mana kiya: token ke paas zaroori rights nahi hain."
            code == 404 -> "Repo/file nahi mili, ya token ko is repo ka access nahi hai."
            code == 409 -> "Conflict: branch ya file badal chuki hai. Dobara analyze karein."
            code == 422 -> "GitHub ne request reject ki: $msg"
            else -> "GitHub request fail hui (HTTP $code)."
        }
    }

    private suspend fun send(url: String, method: String, body: String?, accept: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            val job = coroutineContext[Job]
            val handle = job?.invokeOnCompletion { if (it is CancellationException) conn.disconnect() }
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", accept)
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                conn.setRequestProperty("User-Agent", "MAYA-SelfImprove")
                tokenProvider()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } catch (e: SocketTimeoutException) {
                throw GitHubException("GitHub time out ho gaya.")
            } catch (e: IOException) {
                if (job?.isActive == false) throw CancellationException("cancelled")
                throw GitHubException("Network problem. Internet check karein.")
            } finally {
                handle?.dispose()
                conn.disconnect()
            }
        }
}
