package com.maya.ai.selfimprove

data class DiffLine(val kind: Char, val text: String)

/** Shows each edit as removed (-) and added (+) lines, with a little context. */
object DiffRenderer {

    fun forEdit(edit: Edit): List<DiffLine> {
        if (edit.find.isEmpty()) return edit.replace.lines().map { DiffLine('+', it) }
        val a = edit.find.lines()
        val b = edit.replace.lines()
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val out = ArrayList<DiffLine>()
        for (k in maxOf(0, p - 2) until p) out.add(DiffLine(' ', a[k]))
        for (k in p until a.size - s) out.add(DiffLine('-', a[k]))
        for (k in p until b.size - s) out.add(DiffLine('+', b[k]))
        for (k in a.size - s until minOf(a.size, a.size - s + 2)) out.add(DiffLine(' ', a[k]))
        return out
    }

    /** (removed lines, added lines) */
    fun counts(edit: Edit): Pair<Int, Int> {
        val d = forEdit(edit)
        return d.count { it.kind == '-' } to d.count { it.kind == '+' }
    }

    /** Plain-text patch for sharing when GitHub is not available. */
    fun patchText(edits: List<Edit>): String = buildString {
        edits.forEachIndexed { i, e ->
            appendLine("### Edit ${i + 1}: ${e.path}")
            appendLine("# ${e.reason}")
            if (e.find.isEmpty()) appendLine("(NAYI FILE)")
            for (l in forEdit(e)) appendLine("${l.kind} ${l.text}")
            appendLine()
        }
    }
}
