package com.maya.ai.ui.selfimprove

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.MayaApp
import com.maya.ai.selfimprove.DiffLine
import com.maya.ai.selfimprove.DiffRenderer
import com.maya.ai.selfimprove.Edit
import com.maya.ai.selfimprove.Finding
import com.maya.ai.selfimprove.SelfImprove
import com.maya.ai.selfimprove.Severity
import com.maya.ai.ui.settings.SettingsScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Good = Color(0xFF3DDC97)
private val Bad = Color(0xFFFF8A9B)
private val Warn = Color(0xFFFFC857)

@Composable
fun SelfImproveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val maya = ctx.applicationContext as MayaApp
    val uri = LocalUriHandler.current
    val st by SelfImprove.state.collectAsStateWithLifecycle()
    val history by SelfImprove.history.collectAsStateWithLifecycle()
    val tokenOk by SelfImprove.tokenConfigured.collectAsStateWithLifecycle()
    val geminiOk by maya.keys.configured.collectAsStateWithLifecycle()

    var repo by remember { mutableStateOf(SelfImprove.repo()) }
    var token by remember { mutableStateOf("") }
    var setupNote by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf(SelfImprove.takePendingText() ?: "Maya, khud ko behtar banao.") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var allowProtected by remember { mutableStateOf(false) }
    var confirmPr by remember { mutableStateOf(false) }
    var revertId by remember { mutableStateOf<String?>(null) }

    val busy = st.phase == SelfImprove.Phase.READING || st.phase == SelfImprove.Phase.ANALYZING ||
        st.phase == SelfImprove.Phase.GENERATING || st.phase == SelfImprove.Phase.CREATING_PR

    LaunchedEffect(st.findings) {
        selected = st.findings.filter { it.severity != Severity.LOW }.take(3).map { it.id }.toSet()
    }
    LaunchedEffect(st.proposal) { allowProtected = false }

    SettingsScaffold("Self Improve", onBack) {
        Text(
            "Maya apne code ke sudhaar ka PRASTAV banati hai. Kuch bhi apne aap merge ya deploy nahi hota. Badlav tab hi app mein aate hain jab aap PR merge karke naya APK banakar install karein. Isse Gemini model \"train\" nahi hota.",
            fontSize = 13.sp,
        )

        // ----- 1. setup -----
        Text("1. GitHub setup", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = repo, onValueChange = { repo = it }, singleLine = true,
            label = { Text("Repo (owner/repo)") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { setupNote = SelfImprove.saveRepo(repo) ?: "Repo save ho gaya." }) { Text("Repo save karo") }
        Text(
            "PR ke liye token: " + if (tokenOk) "save hai (Keystore mein encrypted)" else "set nahi hai (optional; bina token ke sirf padhna aur patch share karna chalega)",
            fontSize = 13.sp,
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            label = { Text("Fine-grained token (github_pat_...)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val err = SelfImprove.saveToken(token)
                token = ""
                if (err != null) setupNote = err else SelfImprove.checkToken { setupNote = it }
            }) { Text("Token save karo") }
            OutlinedButton(onClick = { SelfImprove.clearToken(); setupNote = "Token hata diya gaya." }, enabled = tokenOk) {
                Text("Token hatao")
            }
        }
        setupNote?.let { Text(it, fontSize = 13.sp, color = Warn) }
        Text(
            "Token sirf is ek repo ke liye, Contents: Read and write + Pull requests: Read and write, chhoti expiry ke saath banayein. Main branch par kuch nahi likha jata; sirf maya/ branch aur PR.",
            fontSize = 12.sp,
        )

        HorizontalDivider()

        // ----- 2. request + analysis -----
        Text("2. Analyze", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = request, onValueChange = { request = it },
            label = { Text("Kya sudhaar chahiye?") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { SelfImprove.start(request) }, enabled = !busy && geminiOk && repo.isNotBlank()) {
                Text("Analyze karo")
            }
            if (busy) OutlinedButton(onClick = SelfImprove::cancel) { Text("Rok do") }
        }
        if (!geminiOk) Text("Gemini API key set nahi hai.", color = Bad, fontSize = 13.sp)
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(st.progress, fontSize = 13.sp)
            }
        }
        st.error?.let { Text(it, color = Bad, fontSize = 13.sp) }
        st.warning?.let { Text(it, color = Warn, fontSize = 13.sp) }

        // ----- report -----
        if (st.phase == SelfImprove.Phase.REPORT_READY || st.findings.isNotEmpty()) {
            Text("${st.filesRead} files padhi gayi, ${st.findings.size} findings", style = MaterialTheme.typography.titleSmall)
            if (st.notAnalyzed.isNotEmpty()) {
                Text(
                    "Yeh files analyze NAHI hui: " + st.notAnalyzed.take(8).joinToString(", ") { it.substringAfterLast('/') } +
                        if (st.notAnalyzed.size > 8) " (+${st.notAnalyzed.size - 8} aur)" else "",
                    fontSize = 12.sp, color = Warn,
                )
            }
            if (st.findings.isEmpty()) Text("Koi finding nahi mili.", fontSize = 13.sp)
            st.findings.forEach { f ->
                FindingRow(f, f.id in selected) { on -> selected = if (on) selected + f.id else selected - f.id }
            }
            if (st.findings.isNotEmpty()) {
                Button(onClick = { SelfImprove.generate(selected) }, enabled = !busy && selected.isNotEmpty()) {
                    Text("Chune hue par patch banao (${selected.size})")
                }
            }
        }

        // ----- proposal -----
        val p = st.proposal
        val v = st.validation
        if (p != null && v != null) {
            HorizontalDivider()
            Text("3. Prastav (review karein)", style = MaterialTheme.typography.titleSmall)
            Text(p.summary.ifBlank { "(summary nahi di)" }, fontSize = 14.sp)
            if (p.benefits.isNotEmpty()) Text("Fayde:\n" + p.benefits.joinToString("\n") { "- $it" }, fontSize = 13.sp)
            if (p.risks.isNotEmpty()) Text("Risk:\n" + p.risks.joinToString("\n") { "- $it" }, fontSize = 13.sp, color = Warn)
            p.edits.forEachIndexed { i, e -> EditCard(i, e, e.path in v.protectedFiles, e.path in v.newFiles) }

            Text(
                "Local checks: " + if (v.ok) "PASS" else "FAIL",
                color = if (v.ok) Good else Bad, fontWeight = FontWeight.Bold,
            )
            v.issues.forEach { Text("x $it", color = Bad, fontSize = 13.sp) }
            v.warnings.forEach { Text("! $it", color = Warn, fontSize = 13.sp) }
            Text(
                "Build aur tests: abhi NAHI chalaye gaye. Ye sirf GitHub Actions chalata hai, PR banne ke baad. Local checks compiler ki jagah nahi hain.",
                fontSize = 13.sp,
            )

            if (v.ok) {
                if (v.protectedFiles.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowProtected, onCheckedChange = { allowProtected = it })
                        Text(
                            "Maine protected files ka badlav dekh liya: " + v.protectedFiles.joinToString { it.substringAfterLast('/') },
                            fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                    }
                }
                Button(
                    onClick = { confirmPr = true },
                    enabled = !busy && tokenOk && (v.protectedFiles.isEmpty() || allowProtected),
                ) { Text("PR banao (nayi branch)") }
                if (!tokenOk) Text("PR ke liye GitHub token chahiye (upar setup). Ya patch share karke khud lagayein.", fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, DiffRenderer.patchText(p.edits))
                    }
                    ctx.startActivity(Intent.createChooser(send, "Patch share karo"))
                }) { Text("Patch share karo") }
                OutlinedButton(onClick = SelfImprove::reject, enabled = !busy) { Text("Reject") }
            }
        }

        // ----- PR created -----
        val url = st.prUrl
        if (st.phase == SelfImprove.Phase.PR_CREATED && url != null) {
            HorizontalDivider()
            Text("PR ban gaya (branch: ${st.branch})", color = Good, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { uri.openUri(url) }) { Text("PR kholo") }
                OutlinedButton(onClick = SelfImprove::refreshBuild) { Text("Build status dekho") }
            }
            st.build?.let { Text(it, fontSize = 13.sp) }
            Text(
                "Merge se pehle GitHub par diff aur Actions ka result dekhein. Merge aap hi karenge, MAYA nahi. Merge ke baad naya APK banakar install karna padega.",
                fontSize = 12.sp,
            )
        }

        // ----- history -----
        if (history.isNotEmpty()) {
            HorizontalDivider()
            Text("History", style = MaterialTheme.typography.titleSmall)
            history.take(8).forEach { h ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)).padding(10.dp)) {
                    Text(
                        h.status + "  " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(h.time)),
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                    Text(h.request.take(80), fontSize = 12.sp)
                    if (h.summary.isNotBlank()) Text(h.summary.take(140), fontSize = 12.sp)
                    if (h.validation.isNotBlank()) Text(h.validation, fontSize = 12.sp)
                    h.build?.let { Text(it, fontSize = 12.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        h.prUrl?.let { TextButton(onClick = { uri.openUri(it) }) { Text("PR kholo") } }
                        if (h.status == "PR_CREATED" && tokenOk) {
                            TextButton(onClick = { revertId = h.id }) { Text("Revert PR banao") }
                        }
                    }
                }
            }
        }
    }

    if (confirmPr) {
        val files = st.validation?.newContents?.keys.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmPr = false },
            title = { Text("PR banaun?") },
            text = {
                Text(
                    "Nayi branch maya/self-improve-... banegi, in files ke commit hongi:\n" +
                        files.joinToString("\n") { "- ${it.substringAfterLast('/')}" } +
                        "\n\nPhir PR khulegi. Main branch par kuch nahi badlega aur merge aap karenge. Build aur tests abhi nahi chale hain.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmPr = false; SelfImprove.createPullRequest(allowProtected) }) { Text("Haan, PR banao") }
            },
            dismissButton = { TextButton(onClick = { confirmPr = false }) { Text("Cancel") } },
        )
    }

    revertId?.let { id ->
        AlertDialog(
            onDismissRequest = { revertId = null },
            title = { Text("Revert PR banaun?") },
            text = {
                Text("Original files wapas rakhne wali nayi PR banegi. Ye tabhi kaam karti hai jab pehli PR merge ho chuki ho. Merge phir bhi aap karenge.")
            },
            confirmButton = { TextButton(onClick = { revertId = null; SelfImprove.revert(id) }) { Text("Haan") } },
            dismissButton = { TextButton(onClick = { revertId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FindingRow(f: Finding, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            val c = when (f.severity) { Severity.HIGH -> Bad; Severity.MEDIUM -> Warn; Severity.LOW -> Color.Gray }
            Text("[${f.severity}] ${f.title}", color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(f.file.substringAfterLast('/') + "  (${f.source})", fontSize = 11.sp, color = Color.Gray)
            if (f.detail.isNotBlank()) Text(f.detail, fontSize = 12.sp)
            if (f.suggestion.isNotBlank()) Text("Fix: ${f.suggestion}", fontSize = 12.sp)
        }
    }
}

@Composable
private fun EditCard(index: Int, e: Edit, isProtected: Boolean, isNew: Boolean) {
    val (removed, added) = DiffRenderer.counts(e)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)).padding(10.dp)) {
        Text(
            "Edit ${index + 1}: ${e.path.substringAfterLast('/')}" + (if (isNew) "  [NAYI FILE]" else "") + (if (isProtected) "  [PROTECTED]" else ""),
            fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (isProtected) Warn else Color.Unspecified,
        )
        Text(e.path, fontSize = 10.sp, color = Color.Gray)
        if (e.reason.isNotBlank()) Text("Wajah: ${e.reason}", fontSize = 12.sp)
        Text("-$removed / +$added lines", fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        DiffBox(DiffRenderer.forEdit(e))
    }
}

@Composable
private fun DiffBox(lines: List<DiffLine>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B0C16))
            .horizontalScroll(rememberScrollState()).padding(8.dp)
    ) {
        lines.take(80).forEach { l ->
            Text(
                "${l.kind} ${l.text}",
                color = when (l.kind) { '+' -> Good; '-' -> Bad; else -> Color.Gray },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false,
            )
        }
        if (lines.size > 80) Text("... (${lines.size - 80} aur lines)", fontSize = 11.sp, color = Color.Gray)
    }
}
