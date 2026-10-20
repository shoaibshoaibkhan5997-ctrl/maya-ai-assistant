package com.maya.ai.selfimprove

enum class Severity { HIGH, MEDIUM, LOW }

data class Finding(
    val id: Int,
    val file: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val suggestion: String,
    val source: String,
)

data class RepoFile(val path: String, val text: String)

/** find == "" means: create a NEW file (only allowed for unit tests). */
data class Edit(val path: String, val find: String, val replace: String, val reason: String)

data class Proposal(
    val summary: String,
    val benefits: List<String>,
    val risks: List<String>,
    val edits: List<Edit>,
)

enum class PathPolicy { ALLOWED, PROTECTED, BLOCKED }

data class ValidationReport(
    val ok: Boolean,
    val issues: List<String>,
    val warnings: List<String>,
    /** Full new text of every file that would change (existing or new). */
    val newContents: Map<String, String>,
    val protectedFiles: List<String>,
    val newFiles: Set<String>,
)

open class SelfImproveException(message: String) : Exception(message)
class GitHubException(message: String) : SelfImproveException(message)
