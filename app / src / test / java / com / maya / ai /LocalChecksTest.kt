package com.maya.ai.selfimprove

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChecksTest {
    private fun f(path: String, text: String) = RepoFile(path, text)

    @Test fun deprecatedLocaleIsFound() {
        val r = LocalChecks.analyze(
            listOf(f("app/src/main/java/A.kt", "package a\nval l = Locale(\"hi\", \"IN\")\n")), emptySet(),
        )
        assertTrue(r.any { it.title.contains("Locale") })
    }

    @Test fun secretIsFlaggedHighWithoutEchoingIt() {
        val key = "AIza" + "b".repeat(35)
        val r = LocalChecks.analyze(listOf(f("app/src/main/java/A.kt", "package a\nval k = \"$key\"\n")), emptySet())
        val hit = r.first { it.severity == Severity.HIGH }
        assertTrue(!hit.detail.contains(key) && !hit.title.contains(key))
    }

    @Test fun cleartextManifestIsFound() {
        val r = LocalChecks.analyze(
            listOf(f(RepoPolicy.MANIFEST, "<application android:usesCleartextTraffic=\"true\"/>")), emptySet(),
        )
        assertTrue(r.any { it.severity == Severity.HIGH })
    }

    @Test fun missingTestForPureKotlinFile() {
        val r = LocalChecks.analyze(
            listOf(f("app/src/main/java/com/maya/ai/domain/Thing.kt", "package x\n\nobject Thing { fun a() = 1 }\n")),
            setOf("app/src/main/java/com/maya/ai/domain/Thing.kt"),
        )
        assertTrue(r.any { it.title.contains("Thing.kt") })
    }

    @Test fun existingTestSilencesIt() {
        val r = LocalChecks.analyze(
            listOf(f("app/src/main/java/com/maya/ai/domain/Thing.kt", "package x\n\nobject Thing { fun a() = 1 }\n")),
            setOf("app/src/main/java/com/maya/ai/domain/Thing.kt", "app/src/test/java/com/maya/ai/ThingTest.kt"),
        )
        assertTrue(r.none { it.title.contains("Unit test") })
    }
}
