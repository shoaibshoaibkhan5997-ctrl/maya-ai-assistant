package com.maya.ai.selfimprove

/** Which repository paths may be read for analysis, and which may be changed by a proposal. */
object RepoPolicy {
    const val MAX_FILES = 70
    const val MAX_FILE_CHARS = 80_000
    const val TEST_ROOT = "app/src/test/java/"
    const val MANIFEST = "app/src/main/AndroidManifest.xml"

    private val GRADLE_FILES = setOf(
        "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    )

    fun isReadable(path: String): Boolean {
        if (path.contains("..") || path.startsWith("/")) return false
        if (path in GRADLE_FILES || path == ".github/workflows/build-apk.yml") return true
        return path.startsWith("app/src/") && (path.endsWith(".kt") || path.endsWith(".xml"))
    }

    fun policy(path: String): PathPolicy {
        val p = path.trim()
        if (p.isEmpty() || p.startsWith("/") || p.contains("..") || p.contains("\\")) return PathPolicy.BLOCKED
        if (p.startsWith(".github/") || p.contains("/selfimprove/")) return PathPolicy.BLOCKED
        if (p.endsWith(".jks") || p.endsWith(".keystore") || p.contains("local.properties") ||
            p.contains("secrets")
        ) return PathPolicy.BLOCKED
        if (p == MANIFEST || p in GRADLE_FILES) return PathPolicy.PROTECTED
        if (p.contains("/security/") || p.endsWith("/service/MayaAccessibilityService.kt") ||
            p.endsWith("/service/MessagingActions.kt")
        ) return PathPolicy.PROTECTED
        if (p.startsWith("app/src/main/java/") && p.endsWith(".kt")) return PathPolicy.ALLOWED
        if (p.startsWith(TEST_ROOT) && p.endsWith(".kt")) return PathPolicy.ALLOWED
        if (p.startsWith("app/src/main/res/") && p.endsWith(".xml")) return PathPolicy.ALLOWED
        return PathPolicy.BLOCKED
    }
}
