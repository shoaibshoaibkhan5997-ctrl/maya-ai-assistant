package com.maya.ai.selfimprove

import android.content.Context
import android.content.SharedPreferences
import com.maya.ai.MayaApp
import com.maya.ai.security.ApiKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Self-Improve workflow: read repo -> analyze -> propose patch -> validate locally ->
 * (after explicit approval) branch + pull request. It never pushes to the default branch, never
 * merges, and never claims a build ran: real builds/tests are GitHub Actions' job on the PR.
 */
object SelfImprove {

    enum class Phase {
        IDLE, READING, ANALYZING, REPORT_READY, GENERATING, PROPOSAL_READY, CREATING_PR, PR_CREATED, FAILED, REJECTED,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val progress: String = "",
        val request: String = "",
        val filesRead: Int = 0,
        val notAnalyzed: List<String> = emptyList(),
        val findings: List<Finding> = emptyList(),
        val proposal: Proposal? = null,
        val validation: ValidationReport? = null,
        val branch: String? = null,
        val prUrl: String? = null,
        val build: String? = null,
        val error: String? = null,
        val warning: String? = null,
    )

    private lateinit var app: MayaApp
    private lateinit var prefs: SharedPreferences
    private lateinit var tokenKeys: ApiKeyManager
    private lateinit var store: ImprovementHistory

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _history = MutableStateFlow<List<ImprovementHistory.Entry>>(emptyList())
    val history: StateFlow<List<ImprovementHistory.Entry>> = _history
    private val _open = MutableStateFlow<String?>(null)
    val openRequest: StateFlow<String?> = _open
    @Volatile private var pendingText: String? = null

    val tokenConfigured: StateFlow<Boolean> get() = tokenKeys.configured

    private var files: Map<String, RepoFile> = emptyMap()
    private var existing: Set<String> = emptySet()
    private var baseBranch: String = ""
    private var entryId: String? = null

    fun init(application: MayaApp) {
        app = application
        prefs = application.getSharedPreferences("maya_selfimprove", Context.MODE_PRIVATE)
        tokenKeys = ApiKeyManager(application, "github")
        store = ImprovementHistory(File(application.filesDir, "selfimprove"))
        _history.value = store.list()
    }

    // ---------- opening the screen from chat ----------

    fun requestOpen(text: String) { _open.value = text }
    fun consumeOpenRequest() { pendingText = _open.value; _open.value = null }
    fun takePendingText(): String? = pendingText.also { pendingText = null }

    // ---------- setup ----------

    fun repo(): String = prefs.getString("repo", "") ?: ""

    /** Returns an error message, or null when saved. */
    fun saveRepo(value: String): String? {
        val v = value.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
        if (!Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(v)) return "Repo ka format owner/repo hona chahiye."
        prefs.edit().putString("repo", v).apply()
        return null
    }

    /** Returns an error message, or null when saved. Only fine-grained tokens are accepted. */
    fun saveToken(token: String): String? {
        val t = token.trim()
        if (t.isEmpty()) return "Token khaali hai."
        if (!t.startsWith("github_pat_")) {
            return "Sirf fine-grained token (github_pat_...) chalega. Classic token bahut wide hota hai."
        }
        tokenKeys.save(t)
        return null
    }

    fun clearToken() = tokenKeys.clear()

    /** Asks GitHub whether the saved token may push to the repo. [onResult] runs on the main thread. */
    fun checkToken(onResult: (String) -> Unit) {
        val rp = parseRepo()
        scope.launch {
            val msg = if (rp == null) {
                "Pehle repo save karein."
            } else try {
                when (client().canPush(rp.first, rp.second)) {
                    true -> "Token theek hai: is repo par likhne ke rights hain."
                    false -> "Token ko is repo par likhne ke rights nahi dikhte (Contents: Read and write chahiye)."
                    null -> "Token save hua, par rights pakke nahi pata chale."
                }
            } catch (e: Exception) {
                e.message ?: "Token check nahi ho paya."
            }
            withContext(Dispatchers.Main) { onResult(msg) }
        }
    }

    // ---------- 1. analyze ----------

    fun start(request: String) {
        if (job?.isActive == true) return
        val rp = parseRepo() ?: return fail("Pehle GitHub repo (owner/repo) save karein.")
        if (!app.keys.configured.value) return fail("Gemini API key set nahi hai. Gemini API screen se key daalein.")
        val req = request.trim().ifEmpty { "Code ko behtar banao" }
        _state.value = State(phase = Phase.READING, progress = "Shuru ho raha hai...", request = req)
        job = scope.launch {
            try {
                analyze(rp.first, rp.second, req)
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.IDLE, progress = "Rok diya gaya.") }
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Analysis fail ho gaya.")
            }
        }
    }

    private suspend fun analyze(owner: String, repo: String, request: String) {
        val gh = client()
        baseBranch = gh.defaultBranch(owner, repo)
        progress("Repo ki file list padh rahi hoon...")
        val tree = gh.listTree(owner, repo, baseBranch)
        existing = tree.map { it.path }.toSet()

        val readable = tree.filter { RepoPolicy.isReadable(it.path) }.sortedBy { it.path }
        val tooBig = readable.filter { it.size > RepoPolicy.MAX_FILE_CHARS }.map { it.path }
        val fit = readable.filter { it.size <= RepoPolicy.MAX_FILE_CHARS }
        val pick = fit.take(RepoPolicy.MAX_FILES)
        val overflow = fit.drop(RepoPolicy.MAX_FILES).map { it.path }

        val read = ArrayList<RepoFile>()
        for ((i, e) in pick.withIndex()) {
            progress("File padh rahi hoon ${i + 1}/${pick.size}")
            read.add(RepoFile(e.path, gh.rawFile(owner, repo, baseBranch, e.path)))
        }
        files = read.associateBy { it.path }

        _state.update { it.copy(phase = Phase.ANALYZING, progress = "Local checks chal rahe hain...") }
        val local = LocalChecks.analyze(read, existing)
        val ai = CodeAnalyzer(GeminiJson(app.keys, app.prefs)).analyzeWithAi(read, request) { progress(it) }

        val all = (local + ai.findings)
            .sortedWith(compareBy<Finding>({ it.severity.ordinal }, { it.file }))
            .take(40)
            .mapIndexed { i, f -> f.copy(id = i + 1) }

        val id = System.currentTimeMillis().toString()
        entryId = id
        store.add(
            ImprovementHistory.Entry(
                id, System.currentTimeMillis(), request, "REPORT", "${all.size} findings", emptyList(),
                "", null, null, null, all.map { it.title }.take(10),
            )
        )
        refreshHistory()
        _state.update {
            it.copy(
                phase = Phase.REPORT_READY, progress = "", filesRead = read.size,
                notAnalyzed = tooBig + overflow + ai.notAnalyzed, findings = all, warning = ai.warning,
            )
        }
    }

    // ---------- 2. patch ----------

    fun generate(ids: Set<Int>) {
        if (job?.isActive == true) return
        val s = _state.value
        val chosen = s.findings.filter { it.id in ids }.take(5)
        if (chosen.isEmpty()) {
            _state.value = s.copy(error = "Kam se kam ek finding chuniye.")
            return
        }
        _state.value = s.copy(phase = Phase.GENERATING, progress = "Patch ban raha hai...", error = null, proposal = null, validation = null)
        job = scope.launch {
            try {
                val engine = CodeImprovementEngine(GeminiJson(app.keys, app.prefs))
                var proposal = engine.propose(s.request, chosen, files, null)
                var report = validate(proposal)
                if (!report.ok && proposal.edits.isNotEmpty()) {
                    progress("Patch mein galtiyan mili, ek baar dobara koshish...")
                    val feedback = report.issues.joinToString("\n") { "- $it" }
                    proposal = engine.propose(s.request, chosen, files, feedback)
                    report = validate(proposal)
                }
                _state.update { it.copy(phase = Phase.PROPOSAL_READY, proposal = proposal, validation = report, progress = "") }
                updateEntry { e ->
                    e.copy(
                        status = if (report.ok) "PROPOSED" else "INVALID", summary = proposal.summary.take(200),
                        files = report.newContents.keys.toList(), validation = validationText(report),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.REPORT_READY, progress = "Rok diya gaya.") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.REPORT_READY, progress = "", error = e.message ?: "Patch nahi ban paya.") }
            }
        }
    }

    private fun validate(p: Proposal): ValidationReport {
        val originals = p.edits.mapNotNull { files[it.path] }.associate { it.path to it.text }
        return PatchValidator.validate(p.edits, originals, existing)
    }

    private fun validationText(r: ValidationReport) =
        (if (r.ok) "Local checks: PASS" else "Local checks: FAIL (${r.issues.size} issues)") +
            ". Build/tests: abhi nahi chalaye gaye."

    // ---------- 3. pull request (only after explicit approval in the UI) ----------

    fun createPullRequest(allowProtected: Boolean) {
        if (job?.isActive == true) return
        val s = _state.value
        val p = s.proposal
        val v = s.validation
        if (p == null || v == null || !v.ok) return setError("Pehle ek valid patch chahiye.")
        if (!tokenKeys.configured.value) return setError("GitHub token set nahi hai.")
        if (v.protectedFiles.isNotEmpty() && !allowProtected) return setError("Protected files ke liye alag confirm zaroori hai.")
        val rp = parseRepo() ?: return setError("Repo set nahi hai.")
        _state.value = s.copy(phase = Phase.CREATING_PR, progress = "GitHub se baat kar rahi hoon...", error = null)
        job = scope.launch {
            try {
                doCreatePr(rp.first, rp.second, p, v, s.request)
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.PROPOSAL_READY, progress = "Rok diya gaya.") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.PROPOSAL_READY, progress = "", error = e.message ?: "PR nahi ban paya.") }
            }
        }
    }

    private suspend fun doCreatePr(owner: String, repo: String, p: Proposal, v: ValidationReport, request: String) {
        val gh = client()
        val base = gh.defaultBranch(owner, repo)
        if (base != baseBranch) throw SelfImproveException("Default branch badal gayi. Dobara analyze karein.")

        // Make sure nothing changed in the repo since the analysis, so we never overwrite newer work.
        val shas = HashMap<String, String>()
        for (path in v.newContents.keys) {
            if (path in v.newFiles) continue
            progress("Check kar rahi hoon: $path")
            val cur = gh.readFile(owner, repo, path, base)
            if (cur.text != files[path]?.text) throw SelfImproveException("$path repo mein badal chuki hai. Dobara analyze karein.")
            shas[path] = cur.sha
        }

        val id = entryId ?: System.currentTimeMillis().toString()
        entryId = id
        store.saveOriginals(id, v.newContents.keys.filter { it !in v.newFiles }.associateWith { files.getValue(it).text })

        val branch = "maya/self-improve-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        if (!branch.startsWith("maya/") || branch == base) throw SelfImproveException("Branch ka naam safe nahi hai.")
        progress("Nayi branch bana rahi hoon...")
        gh.createBranch(owner, repo, branch, gh.branchSha(owner, repo, base))

        for ((path, text) in v.newContents) {
            progress("Commit: ${path.substringAfterLast('/')}")
            val reason = p.edits.firstOrNull { it.path == path }?.reason.orEmpty().take(60)
            gh.commitFile(owner, repo, branch, path, text, shas[path], "MAYA self-improve: $reason")
        }

        progress("Pull request bana rahi hoon...")
        val url = gh.createPullRequest(owner, repo, branch, base, "MAYA self-improve: ${p.summary.take(60)}", prBody(p, v, request))
        _state.update { it.copy(phase = Phase.PR_CREATED, progress = "", branch = branch, prUrl = url, build = null, error = null) }
        updateEntry { it.copy(status = "PR_CREATED", branch = branch, prUrl = url, files = v.newContents.keys.toList(), validation = validationText(v)) }
    }

    private fun prBody(p: Proposal, v: ValidationReport, request: String) = buildString {
        appendLine("## MAYA self-improve (proposal)")
        appendLine()
        appendLine("Request: $request")
        appendLine()
        appendLine(p.summary)
        if (p.benefits.isNotEmpty()) { appendLine(); appendLine("### Benefits"); p.benefits.forEach { appendLine("- $it") } }
        if (p.risks.isNotEmpty()) { appendLine(); appendLine("### Risks"); p.risks.forEach { appendLine("- $it") } }
        appendLine()
        appendLine("### Files")
        v.newContents.keys.forEach { appendLine("- `$it`" + if (it in v.protectedFiles) " (PROTECTED, reviewed by user)" else "") }
        appendLine()
        appendLine("### Validation")
        appendLine("- MAYA local checks (patch applies, brackets balanced, no secrets/permissions): " + if (v.ok) "PASS" else "FAIL")
        appendLine("- Build and unit tests: **NOT run by MAYA.** Check the GitHub Actions result on this PR before merging.")
        appendLine()
        appendLine("Created by MAYA Self-Improve. Generated by an AI: review every line. MAYA never merges; to undo after merging use GitHub's Revert button.")
    }

    // ---------- build status, reject, revert ----------

    fun refreshBuild() {
        val b = _state.value.branch ?: return
        val rp = parseRepo() ?: return
        scope.launch {
            val text = try {
                val r = client().latestRun(rp.first, rp.second, b)
                if (r == null) "Abhi koi GitHub Actions run nahi mili. Thodi der baad dobara dekhein." else describeRun(r)
            } catch (e: Exception) {
                e.message ?: "Build status nahi mila."
            }
            _state.update { it.copy(build = text) }
            updateEntry { it.copy(build = text) }
        }
    }

    private fun describeRun(r: GitHubClient.RunInfo): String = when {
        r.status != "completed" -> "GitHub Actions chal raha hai (${r.status}). ${r.url}"
        r.conclusion == "success" -> "GitHub Actions: build aur tests PASS. ${r.url}"
        else -> "GitHub Actions: ${r.conclusion ?: "unknown"} (FAIL). Log dekhein: ${r.url}"
    }

    fun reject() {
        updateEntry { it.copy(status = "REJECTED") }
        _state.update { it.copy(phase = Phase.REJECTED, proposal = null, validation = null) }
    }

    /** Opens a revert PR that puts the saved original file contents back (after the user confirms). */
    fun revert(id: String) {
        if (job?.isActive == true) return
        if (!tokenKeys.configured.value) return setError("GitHub token set nahi hai.")
        val rp = parseRepo() ?: return setError("Repo set nahi hai.")
        val originals = store.loadOriginals(id)
        if (originals.isEmpty()) return setError("Is entry ki original files maujood nahi hain.")
        _state.update { it.copy(phase = Phase.CREATING_PR, progress = "Revert PR bana rahi hoon...", error = null) }
        job = scope.launch {
            try {
                val gh = client()
                val (owner, repo) = rp
                val base = gh.defaultBranch(owner, repo)
                val changes = ArrayList<Triple<String, String, String>>()
                for ((path, text) in originals) {
                    val cur = gh.readFile(owner, repo, path, base)
                    if (cur.text != text) changes.add(Triple(path, text, cur.sha))
                }
                if (changes.isEmpty()) throw SelfImproveException("Files pehle se original jaisi hain. PR abhi merge nahi hua ho sakta.")
                val branch = "maya/revert-$id"
                gh.createBranch(owner, repo, branch, gh.branchSha(owner, repo, base))
                for ((path, text, sha) in changes) gh.commitFile(owner, repo, branch, path, text, sha, "MAYA revert: ${path.substringAfterLast('/')}")
                val url = gh.createPullRequest(
                    owner, repo, branch, base, "Revert MAYA self-improve ($id)",
                    "Restores the original contents saved before MAYA's self-improve PR. Review before merging; MAYA never merges.",
                )
                _state.update { it.copy(phase = Phase.PR_CREATED, progress = "", branch = branch, prUrl = url, build = null) }
                store.update(id) { it.copy(status = "REVERT_PR", prUrl = url, branch = branch) }
                refreshHistory()
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.IDLE, progress = "Rok diya gaya.") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.IDLE, progress = "", error = e.message ?: "Revert PR nahi ban paya.") }
            }
        }
    }

    // ---------- housekeeping ----------

    fun cancel() { job?.cancel() }

    fun reset() {
        job?.cancel()
        _state.value = State()
    }

    fun clearAll() {
        job?.cancel()
        store.clear()
        tokenKeys.clear()
        prefs.edit().clear().apply()
        files = emptyMap(); existing = emptySet(); entryId = null
        _state.value = State()
        _history.value = emptyList()
    }

    private fun client() = GitHubClient { tokenKeys.get() }

    private fun parseRepo(): Pair<String, String>? =
        repo().split("/").takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { it[0] to it[1] }

    private fun progress(text: String) = _state.update { it.copy(progress = text) }

    private fun fail(msg: String) {
        _state.update { it.copy(phase = Phase.FAILED, error = msg, progress = "") }
    }

    private fun setError(msg: String) { _state.update { it.copy(error = msg) } }

    private fun updateEntry(change: (ImprovementHistory.Entry) -> ImprovementHistory.Entry) {
        entryId?.let { store.update(it, change) }
        refreshHistory()
    }

    private fun refreshHistory() { _history.value = store.list() }
}
