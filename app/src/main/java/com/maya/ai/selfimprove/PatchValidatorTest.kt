package com.maya.ai.selfimprove

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchValidatorTest {
    private val path = "app/src/main/java/com/maya/ai/domain/X.kt"
    private val src = "package com.maya.ai.domain\n\nfun a() {\n    println(\"hi\")\n}\n"
    private val originals = mapOf(path to src)

    private fun run(vararg e: Edit, existing: Set<String> = setOf(path)) =
        PatchValidator.validate(e.toList(), originals, existing)

    @Test fun cleanEditPasses() {
        val r = run(Edit(path, "println(\"hi\")", "println(\"hello\")", "text badla"))
        assertTrue(r.issues.toString(), r.ok)
        assertTrue(r.newContents.getValue(path).contains("hello"))
    }

    @Test fun missingFindFails() = assertFalse(run(Edit(path, "does not exist", "x", "r")).ok)

    @Test fun ambiguousFindFails() {
        val two = mapOf(path to "package p\nfun a() { x() }\nfun b() { x() }\n")
        val r = PatchValidator.validate(listOf(Edit(path, "x()", "y()", "r")), two, setOf(path))
        assertFalse(r.ok)
    }

    @Test fun sameFindAndReplaceFails() = assertFalse(run(Edit(path, "println(\"hi\")", "println(\"hi\")", "r")).ok)

    @Test fun unbalancedResultFails() = assertFalse(run(Edit(path, "println(\"hi\")", "println(\"hi\"", "r")).ok)

    @Test fun workflowIsBlocked() {
        val r = PatchValidator.validate(
            listOf(Edit(".github/workflows/build-apk.yml", "a", "b", "r")),
            mapOf(".github/workflows/build-apk.yml" to "a"), emptySet(),
        )
        assertFalse(r.ok)
    }

    @Test fun selfImproveCodeIsBlocked() {
        val p = "app/src/main/java/com/maya/ai/selfimprove/SelfImprove.kt"
        val r = PatchValidator.validate(listOf(Edit(p, "a", "b", "r")), mapOf(p to "a"), setOf(p))
        assertFalse(r.ok)
    }

    @Test fun manifestIsProtectedButAllowedWithFlag() {
        val m = RepoPolicy.MANIFEST
        val r = PatchValidator.validate(
            listOf(Edit(m, "allowBackup=\"true\"", "allowBackup=\"false\"", "r")),
            mapOf(m to "<manifest><application android:allowBackup=\"true\"/></manifest>"), setOf(m),
        )
        assertTrue(r.issues.toString(), r.ok)
        assertEquals(listOf(m), r.protectedFiles)
    }

    @Test fun secretIsRejected() =
        assertFalse(run(Edit(path, "println(\"hi\")", "val k = \"AIza" + "a".repeat(35) + "\"", "r")).ok)

    @Test fun shellExecIsRejected() =
        assertFalse(run(Edit(path, "println(\"hi\")", "Runtime.getRuntime().exec(\"ls\")", "r")).ok)

    @Test fun newTestFileIsAllowed() {
        val t = RepoPolicy.TEST_ROOT + "com/maya/ai/NewTest.kt"
        val r = run(Edit(t, "", "package com.maya.ai\n\nclass NewTest\n", "test jodna"))
        assertTrue(r.issues.toString(), r.ok)
        assertTrue(t in r.newFiles)
    }

    @Test fun newFileOutsideTestsIsRejected() =
        assertFalse(run(Edit("app/src/main/java/com/maya/ai/New.kt", "", "package com.maya.ai\n", "r")).ok)

    @Test fun packageLineChangeIsRejected() =
        assertFalse(run(Edit(path, "package com.maya.ai.domain", "package other", "r")).ok)

    @Test fun balanceHandlesTemplates() =
        assertEquals(null, Balance.problem("fun f(x: Int) { println(\"v=\${x + 1} \${\"a\".length}\") }"))

    @Test fun balanceHandlesRawStringAndChars() =
        assertEquals(null, Balance.problem("val s = \"\"\"{ \"a\": [1] }\"\"\"\nval c = '{'\nfun g() {}"))
}
