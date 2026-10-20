package com.maya.ai.selfimprove

/**
 * Local safety checks for a proposed patch. This is NOT a compiler or a test run: it only proves
 * that every edit applies cleanly, stays inside allowed files, keeps brackets/quotes balanced and
 * does not add secrets, new permissions or code-execution tricks.
 */
object PatchValidator {
    const val MAX_EDITS = 6
    const val MAX_FILES = 3
    const val MAX_NEW_FILES = 2
    const val MAX_CHANGED_LINES = 200
    const val MAX_FILE_CHARS = 120_000

    private val DANGEROUS = listOf(
        Regex("Runtime\\.getRuntime\\(\\)\\.exec") to "shell command chalana",
        Regex("\\bProcessBuilder\\b") to "ProcessBuilder (shell)",
        Regex("\\b(DexClassLoader|PathClassLoader)\\b") to "dynamic code loading",
        Regex("System\\.(load|loadLibrary)\\(") to "native library load karna",
        Regex("<uses-permission") to "naya permission",
        Regex("android\\.permission\\.[A-Z_]+") to "naya permission",
    )

    fun validate(edits: List<Edit>, originals: Map<String, String>, existingPaths: Set<String>): ValidationReport {
        val issues = ArrayList<String>()
        val warnings = ArrayList<String>()
        val working = LinkedHashMap<String, String>()
        val touched = LinkedHashSet<String>()
        val newFiles = LinkedHashSet<String>()
        val protectedFiles = LinkedHashSet<String>()
        var changedLines = 0

        if (edits.isEmpty()) issues.add("Koi edit nahi mila.")
        if (edits.size > MAX_EDITS) issues.add("Bahut zyada edits (max $MAX_EDITS).")
        if (edits.map { it.path }.toSet().size > MAX_FILES) issues.add("Bahut zyada files (max $MAX_FILES).")

        for ((i, e) in edits.withIndex()) {
            val label = "Edit ${i + 1} (${e.path.substringAfterLast('/')})"
            when (RepoPolicy.policy(e.path)) {
                PathPolicy.BLOCKED -> { issues.add("$label: yeh file badalna mana hai."); continue }
                PathPolicy.PROTECTED -> protectedFiles.add(e.path)
                PathPolicy.ALLOWED -> Unit
            }
            if (e.reason.isBlank()) warnings.add("$label: wajah nahi likhi.")

            if (e.find.isEmpty()) {
                if (!e.path.startsWith(RepoPolicy.TEST_ROOT)) {
                    issues.add("$label: nayi file sirf unit tests ke liye allowed hai."); continue
                }
                if (e.path in existingPaths || e.path in working) {
                    issues.add("$label: yeh file pehle se maujood hai."); continue
                }
                if (e.replace.isBlank()) { issues.add("$label: nayi file khaali hai."); continue }
                if (newFiles.size >= MAX_NEW_FILES) { issues.add("$label: bahut zyada nayi files."); continue }
                working[e.path] = e.replace
                newFiles.add(e.path)
                touched.add(e.path)
                changedLines += e.replace.lines().size
            } else {
                if (e.find == e.replace) { issues.add("$label: find aur replace same hain."); continue }
                val text = working[e.path] ?: originals[e.path]
                if (text == null) { issues.add("$label: yeh file padhi nahi gayi."); continue }
                val n = occurrences(text, e.find)
                if (n == 0) { issues.add("$label: find text file mein nahi mila."); continue }
                if (n > 1) { issues.add("$label: find text ek se zyada jagah mila ($n)."); continue }
                val idx = text.indexOf(e.find)
                working[e.path] = text.substring(0, idx) + e.replace + text.substring(idx + e.find.length)
                touched.add(e.path)
                changedLines += maxOf(e.find.lines().size, e.replace.lines().size)
            }

            for ((rx, what) in DANGEROUS) {
                if (rx.findAll(e.replace).count() > rx.findAll(e.find).count()) {
                    issues.add("$label: $what jodna mana hai.")
                }
            }
            if (LocalChecks.SECRET_PATTERNS.any { it.containsMatchIn(e.replace) }) {
                issues.add("$label: secret jaisi string hai.")
            }
        }

        if (changedLines > MAX_CHANGED_LINES) issues.add("Badlav bahut bada hai ($changedLines lines, max $MAX_CHANGED_LINES).")

        for (path in touched) {
            val text = working[path] ?: continue
            val name = path.substringAfterLast('/')
            if (text.length > MAX_FILE_CHARS) issues.add("$name bahut badi ho jayegi.")
            if (path.endsWith(".kt")) {
                Balance.problem(text)?.let { issues.add("$name: $it") }
                val before = originals[path]
                if (before != null && packageLine(before) != packageLine(text)) issues.add("$name: package line badli hai.")
            }
            if (path.endsWith(".xml") && !text.trimStart().startsWith("<")) issues.add("$name: XML sahi nahi lagta.")
        }

        val newContents = LinkedHashMap<String, String>()
        for (p in touched) working[p]?.let { newContents[p] = it }
        return ValidationReport(issues.isEmpty(), issues, warnings, newContents, protectedFiles.toList(), newFiles)
    }

    private fun packageLine(s: String) = s.lineSequence().firstOrNull { it.trimStart().startsWith("package ") }?.trim()

    fun occurrences(text: String, find: String): Int {
        if (find.isEmpty()) return 0
        var count = 0
        var i = text.indexOf(find)
        while (i >= 0) { count++; i = text.indexOf(find, i + find.length) }
        return count
    }
}

/** Approximate bracket/quote balance check for Kotlin source (handles strings, templates, comments). */
internal object Balance {
    fun problem(src: String): String? = Scanner(src).run()

    private class Scanner(private val s: String) {
        private var i = 0
        private var problem: String? = null
        private val stack = ArrayList<Char>()

        fun run(): String? {
            code(top = true)
            if (problem == null && stack.isNotEmpty()) problem = "'${stack.last()}' band nahi hua"
            return problem
        }

        private fun fail(msg: String) { if (problem == null) problem = msg }

        private fun pop(c: Char) {
            val open = stack.removeLastOrNull()
            if (open == null) { fail("extra '$c' mila"); return }
            val ok = (open == '(' && c == ')') || (open == '[' && c == ']') || (open == '{' && c == '}')
            if (!ok) fail("'$open' ke saath '$c' match nahi hua")
        }

        private fun code(top: Boolean) {
            var depth = 0
            while (i < s.length && problem == null) {
                val c = s[i]
                when {
                    s.startsWith("//", i) -> { while (i < s.length && s[i] != '\n') i++ }
                    s.startsWith("/*", i) -> {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) { fail("comment band nahi hua"); return }
                        i = end + 2
                    }
                    s.startsWith("\"\"\"", i) -> str(raw = true)
                    c == '"' -> str(raw = false)
                    c == '\'' -> chr()
                    c == '(' || c == '[' -> { stack.add(c); i++ }
                    c == '{' -> { stack.add(c); depth++; i++ }
                    c == ')' || c == ']' -> { pop(c); i++ }
                    c == '}' -> {
                        if (!top && depth == 0) return      // closes a ${...} template
                        pop(c); depth--; i++
                    }
                    else -> i++
                }
            }
        }

        private fun str(raw: Boolean) {
            i += if (raw) 3 else 1
            while (i < s.length && problem == null) {
                val c = s[i]
                when {
                    !raw && c == '\\' -> i += 2
                    !raw && c == '\n' -> { fail("string line ke end tak band nahi hui"); return }
                    !raw && c == '"' -> { i++; return }
                    raw && s.startsWith("\"\"\"", i) -> {
                        i += 3
                        while (i < s.length && s[i] == '"') i++
                        return
                    }
                    c == '$' && i + 1 < s.length && s[i + 1] == '{' -> {
                        i += 2
                        code(top = false)
                        if (problem != null) return
                        if (i < s.length && s[i] == '}') i++ else { fail("string template band nahi hua"); return }
                    }
                    else -> i++
                }
            }
            if (problem == null) fail("string band nahi hui")
        }

        private fun chr() {
            i++
            while (i < s.length && s[i] != '\'' && s[i] != '\n') {
                if (s[i] == '\\') i++
                i++
            }
            if (i < s.length && s[i] == '\'') i++ else fail("char literal band nahi hua")
        }
    }
}
