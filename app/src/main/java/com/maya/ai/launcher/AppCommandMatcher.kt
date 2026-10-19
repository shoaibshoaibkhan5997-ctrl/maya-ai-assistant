package com.maya.ai.launcher

enum class AppTarget(val label: String) {
    YOUTUBE("YouTube"),
    WHATSAPP("WhatsApp"),
    INSTAGRAM("Instagram"),
    CHROME("Chrome"),
    GOOGLE("Google"),
    SETTINGS("Settings"),
    GALLERY("Gallery / Photos"),
    PLAY_STORE("Play Store"),
}

/**
 * Detects a plain "open <app>" command in Hindi / Hinglish / English, without any network or AI.
 * Anything with extra words (e.g. "search karo") is NOT matched and goes to the normal flow.
 */
object AppCommandMatcher {

    private class Hit(val target: AppTarget, val start: Int, val len: Int)

    private val ALIASES: List<Pair<List<String>, AppTarget>> = listOf(
        AppTarget.YOUTUBE to listOf("youtube", "you tube", "yt", "यूट्यूब", "यूटयूब", "यू ट्यूब"),
        AppTarget.WHATSAPP to listOf(
            "whatsapp", "whats app", "whatsap", "watsapp", "whatapp",
            "वाट्सऐप", "व्हाट्सऐप", "व्हाट्सएप", "वॉट्सऐप", "वाट्सएप", "वॉट्सएप", "व्हाट्सअप", "वाट्सअप",
        ),
        AppTarget.INSTAGRAM to listOf("instagram", "insta", "insta gram", "इंस्टाग्राम", "इंस्टा"),
        AppTarget.CHROME to listOf("chrome", "google chrome", "क्रोम", "गूगल क्रोम"),
        AppTarget.GOOGLE to listOf("google", "गूगल"),
        AppTarget.SETTINGS to listOf("settings", "setting", "सेटिंग", "सेटिंग्स", "सेटिंग्ज", "सेटिंगस"),
        AppTarget.GALLERY to listOf(
            "gallery", "photos", "photo", "google photos", "gphotos",
            "गैलरी", "फोटो", "फोटोज", "गूगल फोटोज", "गूगल फोटो",
        ),
        AppTarget.PLAY_STORE to listOf(
            "play store", "playstore", "play stor", "google play", "google play store",
            "प्ले स्टोर", "प्लेस्टोर", "गूगल प्ले",
        ),
    ).flatMap { (target, names) -> names.map { it.split(" ") to target } }
        .sortedByDescending { it.first.size }          // longest name first: "google photos" before "google"

    private val VERBS = setOf(
        "kholo", "kholna", "khol", "kholiye", "kholen", "kholdo", "kholde", "kholie",
        "open", "launch", "start", "chalu", "shuru", "chalao", "chala", "jao", "ja",
        "खोलो", "खोल", "खोलिए", "खोलिये", "खोलें", "खोलना", "ओपन", "स्टार्ट", "चालू", "चलाओ", "शुरू", "जाओ",
    )

    private val FILLERS = setOf(
        "maya", "maaya", "मया", "माया", "please", "plz", "pls", "zara", "jara", "ek", "baar", "bar",
        "app", "application", "aap", "ko", "ka", "ki", "ke", "par", "pe", "mein", "me",
        "ab", "abhi", "jaldi", "mujhe", "muje", "mere", "liye", "hai", "h", "na",
        "karo", "kar", "do", "de", "dijiye", "dijie", "kijiye", "kijie", "kardo", "krdo", "karna", "karen",
        "bhai", "yaar", "hey", "hi", "ok", "okay",
        "ऐप", "एप", "एप्प", "एप्लीकेशन", "को", "में", "पर", "करो", "कर", "दो", "दीजिये", "दीजिए",
        "कीजिये", "कीजिए", "मुझे", "अब", "अभी", "जल्दी", "है", "जरा",
    )

    /** Returns the app to open, or null when the text is not a plain "open this app" command. */
    fun match(text: String): AppTarget? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        val hit = findAlias(tokens) ?: return null
        val rest = tokens.subList(0, hit.start) + tokens.subList(hit.start + hit.len, tokens.size)
        if (findAlias(rest) != null) return null                  // two apps named: ambiguous
        if (rest.none { it in VERBS }) return null                // no "open" word: not a command
        if (rest.any { it !in VERBS && it !in FILLERS }) return null  // extra words: leave to Gemini
        return hit.target
    }

    private fun findAlias(tokens: List<String>): Hit? {
        for ((name, target) in ALIASES) {
            val i = indexOfSub(tokens, name)
            if (i >= 0) return Hit(target, i, name.size)
        }
        return null
    }

    private fun indexOfSub(tokens: List<String>, sub: List<String>): Int {
        if (sub.isEmpty() || sub.size > tokens.size) return -1
        for (i in 0..tokens.size - sub.size) {
            if (tokens.subList(i, i + sub.size) == sub) return i
        }
        return -1
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace("\u093C", "")                                   // Devanagari nukta
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
}
