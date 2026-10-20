package com.maya.ai.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test fun removesEmoji() =
        assertEquals("Aww, thank you!", SpeechText.clean("Aww, thank you! 😊"))

    @Test fun removesHeartWithVariationSelector() =
        assertEquals("Kya hua? Main hoon na", SpeechText.clean("Kya hua? Main hoon na ❤️"))

    @Test fun removesSkinToneAndJoiners() =
        assertEquals("Shabaash hero!", SpeechText.clean("Shabaash hero! 💪🏽"))

    @Test fun keepsHindiAndPunctuation() =
        assertEquals("नमस्ते, कैसे हो?", SpeechText.clean("नमस्ते, कैसे हो?"))

    @Test fun removesMarkdownMarks() =
        assertEquals("bold text", SpeechText.clean("**bold** text"))

    @Test fun onlyEmojiBecomesEmpty() =
        assertEquals("", SpeechText.clean("😊❤️"))
}
