package com.maya.ai.selfimprove

/** Recognises "Maya, khud ko behtar banao" style commands on the phone, before any chat call. */
object SelfImproveMatcher {

    private val IMPROVE = setOf(
        "behtar", "better", "improve", "sudhaar", "sudhar", "sudharo", "upgrade", "optimize", "optimise",
    )
    private val CHECK = setOf(
        "check", "jaancho", "jaanch", "dhoondo", "dhundo", "dhundh", "find", "analyze", "analyse",
        "review", "scan", "problems", "problem", "bugs", "bug", "galtiyan", "galti",
    )
    private val SELF = setOf("khud", "apni", "apne", "apna", "self", "yourself", "your", "own")
    private val CODE = setOf("code", "coding", "source")
    private val NEW = setOf("naya", "naye", "nayi", "new")
    private val FEATURE = setOf("feature", "features")
    private val BUILD = setOf("develop", "banao", "bana", "add", "jodo", "jodna", "create", "implement")
    /** Talking about oneself (not about MAYA's code) is never this command. */
    private val ABOUT_USER = setOf("mujhe", "mera", "meri", "mere", "main", "mai", "hum", "humein")

    fun matches(text: String): Boolean {
        val t = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.isEmpty() || t.size > 14) return false
        if (t.any { it in ABOUT_USER }) return false
        val has = { s: Set<String> -> t.any { it in s } }
        val joined = t.joinToString(" ")
        if ("self improve" in joined || "self improvement" in joined || "self coding" in joined) return true
        if ("khud" in t && has(IMPROVE)) return true
        if (has(SELF) && has(CODE) && (has(CHECK) || has(IMPROVE))) return true
        if (has(NEW) && has(FEATURE) && has(BUILD)) return true
        return false
    }
}
