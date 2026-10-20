package com.maya.ai.selfimprove

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local-only improvement history in a small JSON file (no Firebase, no database). */
class ImprovementHistory(private val dir: File) {

    data class Entry(
        val id: String,
        val time: Long,
        val request: String,
        val status: String,          // REPORT, PROPOSED, INVALID, REJECTED, PR_CREATED, REVERT_PR
        val summary: String,
        val files: List<String>,
        val validation: String,
        val branch: String?,
        val prUrl: String?,
        val build: String?,
        val ideas: List<String>,
    )

    private val historyFile get() = File(dir, "history.json")
    private fun originalsFile(id: String) = File(dir, "originals-$id.json")

    @Synchronized
    fun list(): List<Entry> {
        val f = historyFile
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(e: Entry) = write(listOf(e) + list())

    @Synchronized
    fun update(id: String, change: (Entry) -> Entry) = write(list().map { if (it.id == id) change(it) else it })

    @Synchronized
    fun saveOriginals(id: String, files: Map<String, String>) {
        dir.mkdirs()
        val o = JSONObject()
        files.forEach { (k, v) -> o.put(k, v) }
        originalsFile(id).writeText(o.toString())
    }

    @Synchronized
    fun loadOriginals(id: String): Map<String, String> {
        val f = originalsFile(id)
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun clear() { dir.deleteRecursively() }

    private fun write(entries: List<Entry>) {
        dir.mkdirs()
        val kept = entries.take(MAX_ENTRIES)
        historyFile.writeText(JSONArray().also { a -> kept.forEach { a.put(toJson(it)) } }.toString())
        // drop original-copies of entries that fell out of the list
        dir.listFiles()?.filter { it.name.startsWith("originals-") && kept.none { e -> it.name == "originals-${e.id}.json" } }
            ?.forEach { it.delete() }
    }

    private fun toJson(e: Entry) = JSONObject()
        .put("id", e.id).put("time", e.time).put("request", e.request).put("status", e.status)
        .put("summary", e.summary).put("files", JSONArray(e.files)).put("validation", e.validation)
        .put("branch", e.branch ?: JSONObject.NULL).put("prUrl", e.prUrl ?: JSONObject.NULL)
        .put("build", e.build ?: JSONObject.NULL).put("ideas", JSONArray(e.ideas))

    private fun fromJson(o: JSONObject): Entry {
        fun nullable(k: String) = if (o.isNull(k)) null else o.optString(k)
        fun list(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }
        return Entry(
            o.optString("id"), o.optLong("time"), o.optString("request"), o.optString("status"),
            o.optString("summary"), list("files"), o.optString("validation"),
            nullable("branch"), nullable("prUrl"), nullable("build"), list("ideas"),
        )
    }

    private companion object { const val MAX_ENTRIES = 50 }
}
