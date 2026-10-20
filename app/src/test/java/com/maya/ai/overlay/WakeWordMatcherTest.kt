package com.maya.ai.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {
    @Test fun heyMayaJson() = assertTrue(WakeWordMatcher.matches("""{"text" : "hey maya"}"""))
    @Test fun hayMaya() = assertTrue(WakeWordMatcher.matches("""{"text" : "hay maya"}"""))
    @Test fun hiMaya() = assertTrue(WakeWordMatcher.matches("""{"text" : "hi maya"}"""))
    @Test fun plainText() = assertTrue(WakeWordMatcher.matchesText("Hey Maya"))
    @Test fun unknownOnly() = assertFalse(WakeWordMatcher.matches("""{"text" : "[unk]"}"""))
    @Test fun nameWithoutGreeting() = assertFalse(WakeWordMatcher.matches("""{"text" : "maya"}"""))
    @Test fun greetingWithoutName() = assertFalse(WakeWordMatcher.matches("""{"text" : "hey"}"""))
    @Test fun emptyAndNull() {
        assertFalse(WakeWordMatcher.matches(""))
        assertFalse(WakeWordMatcher.matches(null))
    }
}
