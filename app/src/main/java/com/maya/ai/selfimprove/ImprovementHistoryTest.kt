package com.maya.ai.selfimprove

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImprovementHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(id: String) = ImprovementHistory.Entry(
        id, 1L, "req", "REPORT", "s", listOf("a.kt"), "v", null, null, null, listOf("idea"),
    )

    @Test fun addListAndUpdate() {
        val h = ImprovementHistory(tmp.newFolder("h"))
        h.add(entry("1"))
        h.add(entry("2"))
        assertEquals(listOf("2", "1"), h.list().map { it.id })
        h.update("1") { it.copy(status = "PR_CREATED", prUrl = "https://x") }
        val e = h.list().first { it.id == "1" }
        assertEquals("PR_CREATED", e.status)
        assertEquals("https://x", e.prUrl)
        assertNull(e.branch)
    }

    @Test fun originalsRoundTrip() {
        val h = ImprovementHistory(tmp.newFolder("o"))
        h.saveOriginals("7", mapOf("a/b.kt" to "line1\nline2"))
        assertEquals("line1\nline2", h.loadOriginals("7")["a/b.kt"])
    }

    @Test fun clearRemovesEverything() {
        val h = ImprovementHistory(tmp.newFolder("c"))
        h.add(entry("1"))
        h.saveOriginals("1", mapOf("p" to "t"))
        h.clear()
        assertTrue(h.list().isEmpty() && h.loadOriginals("1").isEmpty())
    }
}
