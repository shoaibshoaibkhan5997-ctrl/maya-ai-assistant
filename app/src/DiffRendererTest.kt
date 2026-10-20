package com.maya.ai.selfimprove

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRendererTest {
    @Test fun countsOnlyChangedLines() {
        val e = Edit("p", "a\nb\nc", "a\nB\nc", "r")
        assertEquals(1 to 1, DiffRenderer.counts(e))
    }

    @Test fun newFileIsAllAdded() {
        val e = Edit("p", "", "x\ny", "r")
        assertTrue(DiffRenderer.forEdit(e).all { it.kind == '+' })
    }

    @Test fun patchTextMentionsPathAndReason() {
        val t = DiffRenderer.patchText(listOf(Edit("a/b.kt", "x", "y", "wajah")))
        assertTrue(t.contains("a/b.kt") && t.contains("wajah") && t.contains("- x") && t.contains("+ y"))
    }
}
