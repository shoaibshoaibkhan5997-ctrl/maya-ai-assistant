package com.maya.ai.selfimprove

/** Cheap checks that run on the phone without any AI. Pure Kotlin, so they can be unit tested. */
object LocalChecks {

    val SECRET_PATTERNS = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{35}"),
        Regex("ghp_[A-Za-z0-9]{30,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("sk_[0-9a-fA-F]{24,}"),
    )

    private val DEPRECATED = listOf(
        Regex("\\bAsyncTask\\b") to "AsyncTask deprecated hai; coroutines use karein.",
        Regex("startActivityForResult\\(") to "startActivityForResult deprecated hai; Activity Result API use karein.",
        Regex("\\bLocale\\(\"") to "Locale(String, String) constructor deprecated hai; Locale.forLanguageTag use karein.",
        Regex("\\bHandler\\(\\)") to "Handler() bina Looper ke deprecated hai.",
        Regex("\\bGlobalScope\\b") to "GlobalScope se leaks ho sakte hain; scoped coroutine use karein.",
    )

    private val LOG_SECRET = Regex("(?i)\\b(Log\\.[a-z]|println)\\s*\\(.*(key|token|secret|password)")
    private val EMPTY_CATCH = Regex("catch\\s*\\(\\s*(?!_\\b)[A-Za-z]\\w*\\s*:[^)]*\\)\\s*\\{\\s*\\}")
    private val BANG = Regex("!!(?!=)")
    private val TODO = Regex("(?i)\\b(TODO|FIXME)\\b")

    fun analyze(files: List<RepoFile>, existingPaths: Set<String>): List<Finding> {
        val out = ArrayList<Finding>()
        for (f in files) {
            if (f.path.endsWith(".kt")) out += kotlinChecks(f)
            if (f.path == RepoPolicy.MANIFEST) out += manifestChecks(f)
            if (f.path == "app/build.gradle.kts") out += gradleChecks(f)
        }
        out += missingTests(files, existingPaths)
        out += duplicates(files.filter { it.path.endsWith(".kt") })
        return out
    }

    private fun finding(path: String, sev: Severity, title: String, detail: String, fix: String) =
        Finding(0, path, sev, title, detail, fix, "local")

    private fun kotlinChecks(f: RepoFile): List<Finding> {
        val out = ArrayList<Finding>()
        val lines = f.text.lines()
        val code = lines.filter { val t = it.trimStart(); !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") }

        for ((rx, msg) in DEPRECATED) {
            val n = code.count { rx.containsMatchIn(it) }
            if (n > 0) out += finding(f.path, Severity.LOW, msg, "$n jagah is file mein mila.", "Naye API se badlein, behaviour same rakhte hue.")
        }
        val bangs = code.sumOf { BANG.findAll(it).count() }
        if (bangs >= 4) out += finding(f.path, Severity.LOW, "Zyada !! (non-null assertion)", "$bangs jagah !! use hua hai; crash ka risk.", "Safe call (?.) ya null check use karein.")
        val empty = EMPTY_CATCH.findAll(f.text).count()
        if (empty > 0) out += finding(f.path, Severity.LOW, "Khaali catch block", "$empty khaali catch block errors chhupa sakte hain.", "Kam se kam error ko handle ya report karein.")
        if (code.any { LOG_SECRET.containsMatchIn(it) }) {
            out += finding(f.path, Severity.HIGH, "Log mein key/token ka zikr", "Log ya print mein key/token/password jaisa shabd hai.", "Secret kabhi log na karein.")
        }
        if (SECRET_PATTERNS.any { it.containsMatchIn(f.text) }) {
            out += finding(f.path, Severity.HIGH, "Code mein secret jaisi string", "Koi API key/token jaisi string source code mein dikh rahi hai.", "Use turant hatayein aur key badal dein.")
        }
        if (lines.size > 450) out += finding(f.path, Severity.LOW, "File bahut badi hai", "${lines.size} lines. Samajhna aur test karna mushkil.", "Chhoti files/classes mein baantein.")
        val todos = TODO.findAll(f.text).count()
        if (todos > 0) out += finding(f.path, Severity.LOW, "TODO/FIXME baaki hain", "$todos jagah.", "Unhe poora karein ya hata dein.")
        return out
    }

    private fun manifestChecks(f: RepoFile): List<Finding> {
        val out = ArrayList<Finding>()
        if (f.text.contains("usesCleartextTraffic=\"true\"")) {
            out += finding(f.path, Severity.HIGH, "Cleartext (http) traffic allowed hai", "App unencrypted network allow karti hai.", "usesCleartextTraffic=false rakhein.")
        }
        if (f.text.contains("allowBackup=\"true\"")) {
            out += finding(f.path, Severity.MEDIUM, "allowBackup=true", "App data backup ho sakta hai (settings/keys ka risk).", "allowBackup=false rakhein.")
        }
        return out
    }

    private fun gradleChecks(f: RepoFile): List<Finding> {
        val out = ArrayList<Finding>()
        val target = Regex("targetSdk\\s*=\\s*(\\d+)").find(f.text)?.groupValues?.get(1)?.toIntOrNull()
        if (target != null && target < 34) {
            out += finding(f.path, Severity.MEDIUM, "targetSdk purana hai ($target)", "Naye Android rules lagu nahi hote.", "targetSdk badhane par pehle testing karein.")
        }
        if (Regex("isMinifyEnabled\\s*=\\s*false").containsMatchIn(f.text)) {
            out += finding(f.path, Severity.LOW, "Release build mein minify band hai", "APK bada aur code asaani se padha ja sakta hai.", "Release ke liye R8 minify soch-samajhkar ON karein.")
        }
        return out
    }

    /** Pure-Kotlin files (no Android imports) that have no <Name>Test.kt are easy to cover. */
    private fun missingTests(files: List<RepoFile>, existing: Set<String>): List<Finding> {
        val tests = existing.filter { it.startsWith(RepoPolicy.TEST_ROOT) }
            .map { it.substringAfterLast('/').removeSuffix(".kt") }.toSet()
        return files.filter { f ->
            f.path.startsWith("app/src/main/java/") && f.path.endsWith(".kt") &&
                !f.text.contains("import android.") && !f.text.contains("import androidx.") &&
                (f.text.contains("\nobject ") || f.text.contains("\nclass ") || f.text.contains("\nfun ")) &&
                (f.path.substringAfterLast('/').removeSuffix(".kt") + "Test") !in tests
        }.take(3).map {
            finding(
                it.path, Severity.LOW, "Unit test nahi mila: ${it.path.substringAfterLast('/')}",
                "Is pure-Kotlin file ke liye <Naam>Test.kt nahi hai (shayad kisi aur naam ke test mein cover ho).",
                "Chhota unit test jodein.",
            )
        }
    }

    /** Blocks of 6 identical lines that appear in two different files. */
    private fun duplicates(files: List<RepoFile>): List<Finding> {
        val seen = HashMap<String, String>()
        val pairs = HashMap<Pair<String, String>, Int>()
        for (f in files) {
            val kept = f.text.lines().map { it.trim() }.filter {
                it.length > 12 && !it.startsWith("import ") && !it.startsWith("//") &&
                    !it.startsWith("*") && !it.startsWith("/*")
            }
            for (k in 0..kept.size - 6) {
                val key = kept.subList(k, k + 6).joinToString("\n")
                val first = seen[key]
                if (first == null) seen[key] = f.path
                else if (first != f.path) pairs.merge(first to f.path, 1, Int::plus)
            }
        }
        return pairs.entries.filter { it.value >= 4 }.sortedByDescending { it.value }.take(2).map { (pair, n) ->
            finding(
                pair.first, Severity.LOW, "Duplicate code",
                "${pair.first.substringAfterLast('/')} aur ${pair.second.substringAfterLast('/')} mein milta-julta code (~$n jagah).",
                "Common code ko ek helper mein nikalein.",
            )
        }
    }
}
