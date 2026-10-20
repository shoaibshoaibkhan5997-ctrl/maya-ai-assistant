package com.maya.ai.selfimprove

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoPolicyTest {
    @Test fun mainKotlinIsAllowed() =
        assertEquals(PathPolicy.ALLOWED, RepoPolicy.policy("app/src/main/java/com/maya/ai/ui/Screen.kt"))

    @Test fun workflowsAreBlocked() = assertEquals(PathPolicy.BLOCKED, RepoPolicy.policy(".github/workflows/build-apk.yml"))
    @Test fun gradleIsProtected() = assertEquals(PathPolicy.PROTECTED, RepoPolicy.policy("app/build.gradle.kts"))
    @Test fun securityIsProtected() =
        assertEquals(PathPolicy.PROTECTED, RepoPolicy.policy("app/src/main/java/com/maya/ai/security/ApiKeyManager.kt"))
    @Test fun traversalIsBlocked() = assertEquals(PathPolicy.BLOCKED, RepoPolicy.policy("app/src/main/java/../../x.kt"))
    @Test fun keystoreIsBlocked() = assertEquals(PathPolicy.BLOCKED, RepoPolicy.policy("app/release.jks"))
    @Test fun readableKotlin() = assertTrue(RepoPolicy.isReadable("app/src/main/java/com/maya/ai/MayaApp.kt"))
    @Test fun imageNotReadable() = assertFalse(RepoPolicy.isReadable("app/src/main/res/mipmap/ic.png"))
}
