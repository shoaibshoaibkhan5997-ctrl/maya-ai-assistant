package com.maya.ai.speech

/**
 * Prepares text for speaking. Emojis and markdown marks stay in the chat text but are removed
 * from what is spoken, because text-to-speech engines may read an emoji out by its name.
 */
object SpeechText {

    fun clean(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!isEmojiLike(cp)) sb.appendCodePoint(cp)
        }
        return sb.toString()
            .replace(Regex("[*_`#>~]"), "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }

    private fun isEmojiLike(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||      // emoticons, symbols, flags, skin tones, newer emoji
            cp in 0x2600..0x27BF ||    // misc symbols, dingbats (hearts, stars, check marks)
            cp in 0x2B00..0x2BFF ||    // arrows and stars
            cp in 0x2300..0x23FF ||    // misc technical (watch, hourglass)
            cp in 0xFE00..0xFE0F ||    // variation selectors
            cp == 0x200D ||            // zero width joiner
            cp == 0x20E3 ||            // keycap
            cp == 0x2764 || cp == 0x2122 || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049
}
