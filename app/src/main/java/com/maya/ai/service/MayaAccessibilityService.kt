package com.maya.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.maya.ai.domain.ActionResult
import com.maya.ai.domain.AssistantCommand
import com.maya.ai.domain.ScrollDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What is on screen right before Send: best-effort chat name and the typed message. */
data class SendContext(val app: String, val title: String?, val draft: String)

/**
 * Executes ONLY the allowlisted [AssistantCommand] types, and only while the user has
 * turned Screen Control ON inside MAYA. It never sends screen contents anywhere; it only
 * looks at node structure locally to find a button, a list item or the focused input field.
 * Payment-like labels and bank/wallet apps are refused. Send is limited to messaging apps.
 */
class MayaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* intentionally unused */ }
    override fun onInterrupt() { /* nothing long-running to interrupt */ }

    fun execute(cmd: AssistantCommand): ActionResult {
        if (!acceptingCommands) {
            return ActionResult(false, "Screen Control OFF hai. Action nahi chalaya.")
        }
        return when (cmd) {
            is AssistantCommand.OpenApp -> openApp(cmd.appName)
            is AssistantCommand.SearchYoutube -> searchYoutube(cmd.query)
            is AssistantCommand.SearchInApp -> searchInApp(cmd.query)
            is AssistantCommand.TapText -> tapText(cmd.label)
            is AssistantCommand.TapFirstResult -> tapFirstResult()
            is AssistantCommand.Scroll -> scroll(cmd.direction)
            is AssistantCommand.NavigateBack ->
                if (performGlobalAction(GLOBAL_ACTION_BACK)) ActionResult(true, "Back action bhej diya gaya.")
                else ActionResult(false, "Back action Android ne accept nahi kiya.")
            is AssistantCommand.Call -> MessagingActions.dial(this, cmd.number)
            is AssistantCommand.WhatsAppMessage -> MessagingActions.whatsApp(this, cmd.number, cmd.text)
            is AssistantCommand.SmsMessage -> MessagingActions.sms(this, cmd.number, cmd.text)
            is AssistantCommand.TypeText -> typeText(cmd.text)
            is AssistantCommand.PressSend -> pressSend()
            else -> ActionResult(false, "Yeh command device action nahi hai.")
        }
    }

    // ---------- opening apps ----------

    private fun openApp(name: String): ActionResult {
        val pm = packageManager
        val lower = name.lowercase()
        if (lower == "settings" || lower == "setting") {
            return launch(Intent(Settings.ACTION_SETTINGS), "Settings")
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return ActionResult(false, "\"$name\" naam ka koi installed app nahi mila.")
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return ActionResult(false, "\"$name\" ko launch nahi kar sakte.")
        return launch(intent, match.loadLabel(pm).toString())
    }

    /** Opens the YouTube search results page. Playing a video is a separate tap step. */
    private fun searchYoutube(query: String): ActionResult {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(Intent(base).setPackage("com.google.android.youtube"))
            ActionResult(true, "YouTube par \"$query\" ka search kholne ka request bhej diya gaya.")
        } catch (e: Exception) {
            try {
                startActivity(base)
                ActionResult(true, "\"$query\" ka YouTube search browser mein kholne ka request bhej diya gaya.")
            } catch (e2: Exception) {
                ActionResult(false, "YouTube search nahi khul paya.")
            }
        }
    }

    private fun launch(intent: Intent, label: String): ActionResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        // Android does not tell us the app reached the foreground, so we say only what we know.
        ActionResult(true, "$label kholne ka request bhej diya gaya.")
    } catch (e: Exception) {
        ActionResult(false, "$label nahi khul paya.")
    }

    // ---------- tapping and searching ----------

    private fun tapText(label: String): ActionResult {
        if (SENSITIVE_LABEL.containsMatchIn(label)) {
            return ActionResult(false, "\"$label\" payment/delete/login jaisa sensitive lagta hai, isliye MAYA ise tap nahi karegi.")
        }
        val root = rootInActiveWindow ?: return noScreen()
        if (blockedScreen(root)) return blockedResult()
        val needle = label.lowercase()
        val node = dfsFirst(root) { it.isVisibleToUser && labelOf(it).lowercase().contains(needle) }
            ?: return ActionResult(false, "\"$label\" naam ka button ya item screen par nahi mila.")
        return clickNode(node, label, checkLabel = true)
    }

    /**
     * Taps the first big clickable row below the search bar. The list does not have to be
     * scrollable: short result lists (e.g. 1-2 contacts) fit on screen and are not marked scrollable.
     */
    private fun tapFirstResult(): ActionResult {
        val root = rootInActiveWindow ?: return noScreen()
        if (blockedScreen(root)) return blockedResult()

        val dm = resources.displayMetrics
        val container = collect(root) { it.isScrollable && it.isVisibleToUser }
            .maxByOrNull { area(it) } ?: root

        // Everything above the search box / toolbar is ignored.
        val editable = dfsFirst(root) { it.isEditable && it.isVisibleToUser }
        val headerBottom = if (editable != null) {
            val r = Rect()
            editable.getBoundsInScreen(r)
            r.bottom
        } else {
            (dm.heightPixels * 0.12).toInt()
        }
        val minH = dm.heightPixels / 20
        val minW = dm.widthPixels / 2

        val candidates = collect(container) { n ->
            val r = Rect()
            n.getBoundsInScreen(r)
            !n.isScrollable && n.isClickable && n.isEnabled && n.isVisibleToUser && !n.isEditable &&
                r.top >= headerBottom - 8 && r.height() >= minH && r.width() >= minW &&
                !AD_LABEL.containsMatchIn(labelOf(n))
        }
        val item = candidates.minWithOrNull(
            compareBy<AccessibilityNodeInfo> { n -> Rect().also { n.getBoundsInScreen(it) }.top }
                .thenBy { n -> Rect().also { n.getBoundsInScreen(it) }.left }
        ) ?: return ActionResult(false, "Screen par tap karne layak pehla result nahi mila.")
        return clickNode(item, "pehla result", checkLabel = false)
    }

    private fun searchInApp(query: String): ActionResult {
        val root = rootInActiveWindow ?: return noScreen()
        if (blockedScreen(root)) return blockedResult()

        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: dfsFirst(root) { it.isEditable && it.isVisibleToUser }

        if (field == null) {
            // Try opening the search box via a search icon/button, then the caller retries.
            val btn = dfsFirst(root) {
                it.isVisibleToUser && !it.isEditable && labelOf(it).lowercase().let { l ->
                    l.contains("search") || l.contains("khoj")
                }
            }
            if (btn != null) {
                clickNode(btn, "Search", checkLabel = false)
                return ActionResult(false, "Search icon dabaya, search box khulne ka intezaar hai.")
            }
            return ActionResult(false, "Is screen par search box ya search button nahi mila.")
        }
        if (field.isPassword) return ActionResult(false, "Password field mein MAYA text nahi likhti.")

        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ActionResult(false, "Yeh search box text set karne nahi deta.")
        }
        var submitted = false
        if (Build.VERSION.SDK_INT >= 30) {
            submitted = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        if (!submitted) {
            val btn = dfsFirst(root) {
                it.isVisibleToUser && it.isClickable && !it.isEditable &&
                    labelOf(it).lowercase().contains("search")
            }
            if (btn != null) submitted = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return ActionResult(
            true,
            "\"$query\" search box mein likha gaya" +
                if (submitted) " aur search karne ki request bheji gayi." else ". Search aap khud dabayein."
        )
    }

    private fun clickNode(node: AccessibilityNodeInfo, what: String, checkLabel: Boolean): ActionResult {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.isEnabled) {
                if (checkLabel && SENSITIVE_LABEL.containsMatchIn(labelOf(n))) {
                    return ActionResult(false, "Yeh item sensitive lagta hai, isliye tap nahi kiya.")
                }
                return if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    ActionResult(true, "\"$what\" par tap karne ki request bheji gayi.")
                else ActionResult(false, "\"$what\" ka tap Android ne accept nahi kiya.")
            }
            n = n.parent
            depth++
        }
        return ActionResult(false, "\"$what\" tap karne layak nahi hai.")
    }

    // ---------- scroll and typing ----------

    private fun scroll(direction: ScrollDirection): ActionResult {
        val root = rootInActiveWindow ?: return noScreen()
        val target = collect(root) { it.isScrollable && it.isVisibleToUser }.maxByOrNull { area(it) }
            ?: return ActionResult(false, "Is screen par scroll karne layak kuch nahi mila.")
        val action = if (direction == ScrollDirection.DOWN)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (target.performAction(action)) {
            ActionResult(true, "Scroll ${if (direction == ScrollDirection.DOWN) "neeche" else "upar"} kiya gaya.")
        } else {
            ActionResult(false, "Scroll nahi ho paya (shayad list ke end par hain).")
        }
    }

    private fun typeText(text: String): ActionResult {
        val root = rootInActiveWindow ?: return noScreen()
        if (blockedScreen(root)) return blockedResult()
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: dfsFirst(root) { it.isEditable && it.isVisibleToUser && !it.isPassword }
        if (field == null) {
            return ActionResult(false, "Screen par koi text box nahi mila.")
        }
        if (field.isPassword) {
            return ActionResult(false, "Password field mein MAYA text nahi likhti.")
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            ActionResult(true, "Text text box mein likh diya gaya.")
        else ActionResult(false, "Yeh text box text set karne nahi deta.")
    }

    // ---------- send (messaging apps only) ----------

    /**
     * Reads, locally, what is about to be sent: the chat name (best effort, from the screen's
     * top area) and the typed text. Returns null if the current app is not a messaging app.
     */
    fun readSendContext(): SendContext? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg !in SEND_PACKAGES) return null
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
            ?: dfsFirst(root) { it.isEditable && it.isVisibleToUser && !it.isPassword }
        val draft = if (field == null || field.isShowingHintText) "" else field.text?.toString().orEmpty().trim()
        return SendContext(pkg, chatTitle(root), draft)
    }

    private fun chatTitle(root: AccessibilityNodeInfo): String? {
        val limit = resources.displayMetrics.heightPixels * 0.2f
        val candidates = collect(root) { n ->
            val t = n.text?.toString()?.trim().orEmpty()
            val r = Rect()
            n.getBoundsInScreen(r)
            n.isVisibleToUser && !n.isEditable && t.length in 2..40 && r.bottom <= limit &&
                !TITLE_SKIP.matches(t)
        }
        val best = candidates.minWithOrNull(
            compareBy<AccessibilityNodeInfo> { n -> Rect().also { n.getBoundsInScreen(it) }.top }
                .thenBy { n -> Rect().also { n.getBoundsInScreen(it) }.left }
        )
        return best?.text?.toString()?.trim()
    }

    /** Presses the Send button, but only in a messaging app, never on a payment-looking screen. */
    private fun pressSend(): ActionResult {
        val root = rootInActiveWindow ?: return noScreen()
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg !in SEND_PACKAGES) {
            return ActionResult(false, "Send sirf WhatsApp, Messages ya Telegram jaise messaging apps mein chalta hai.")
        }
        if (blockedScreen(root)) return blockedResult()
        if (dfsFirst(root) { it.isVisibleToUser && PAYMENT_SCREEN.containsMatchIn(labelOf(it)) } != null) {
            return ActionResult(false, "Yeh screen payment jaisi lag rahi hai, isliye Send nahi dabaya.")
        }
        val ctx = readSendContext()
        if (ctx == null || ctx.draft.isBlank()) {
            return ActionResult(false, "Message box khaali hai, isliye Send nahi dabaya.")
        }
        val btn = dfsFirst(root) { n ->
            n.isVisibleToUser && n.isEnabled && labelOf(n).trim().lowercase() in SEND_LABELS
        } ?: return ActionResult(false, "Send button nahi mila.")
        var n: AccessibilityNodeInfo? = btn
        var depth = 0
        while (n != null && depth < 4) {
            if (n.isClickable && n.isEnabled) {
                return if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    ActionResult(true, "Send dabane ki request bheji gayi. Chat mein dekh lein ki message gaya.")
                else ActionResult(false, "Send ka tap Android ne accept nahi kiya.")
            }
            n = n.parent
            depth++
        }
        return ActionResult(false, "Send button tap karne layak nahi mila.")
    }

    // ---------- helpers ----------

    private fun noScreen() = ActionResult(false, "Abhi screen ki jaankari available nahi hai.")

    private fun blockedResult() =
        ActionResult(false, "Yeh app bank/payment jaisa lagta hai, isliye MAYA yahan koi tap ya search nahi karegi.")

    private fun blockedScreen(root: AccessibilityNodeInfo) =
        SENSITIVE_PACKAGE.containsMatchIn(root.packageName?.toString().orEmpty())

    private fun labelOf(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text, n.contentDescription).joinToString(" ")

    private fun area(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.width() * r.height()
    }

    /** Breadth-first list of matching nodes (bounded). */
    private fun collect(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }

    /** Depth-first, top-to-bottom first match (bounded). */
    private fun dfsFirst(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        dfs(root, pred, intArrayOf(MAX_NODES))

    private fun dfs(node: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean, budget: IntArray): AccessibilityNodeInfo? {
        if (budget[0]-- <= 0) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            dfs(c, pred, budget)?.let { return it }
        }
        return null
    }

    companion object {
        private const val MAX_NODES = 2000
        private val SENSITIVE_LABEL = Regex(
            "pay|buy|purchase|order|checkout|confirm|send|transfer|delete|remove|uninstall|install|" +
                "sign ?in|log ?in|password|pin|otp|subscribe|upgrade|donate|bhugtan|kharid|erase|reset",
            RegexOption.IGNORE_CASE
        )
        private val SENSITIVE_PACKAGE = Regex(
            "bank|pay|wallet|upi|paisa|phonepe|paytm|bhim|authenticator",
            RegexOption.IGNORE_CASE
        )
        private val AD_LABEL = Regex("sponsored|\\bad\\b|advert", RegexOption.IGNORE_CASE)

        /** Apps in which "Send" may be pressed (after the user's spoken confirmation). */
        private val SEND_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
        )
        private val SEND_LABELS = setOf("send", "send message", "भेजें", "भेजो", "भेजना")
        private val PAYMENT_SCREEN = Regex(
            "upi pin|enter amount|send money|payment (method|options)|bank account",
            RegexOption.IGNORE_CASE
        )
        private val TITLE_SKIP = Regex(
            "(?i)^(online|typing\\.*…?|last seen.*|tap here for contact info|click here for contact info|\\d+|whatsapp|messages|telegram)$"
        )

        private val _connected = MutableStateFlow(false)
        /** True only while Android has the service enabled and bound. */
        val connected: StateFlow<Boolean> = _connected

        @Volatile var instance: MayaAccessibilityService? = null
            private set

        /** Mirrors MAYA's in-app Screen Control switch. Default OFF. */
        @Volatile var acceptingCommands: Boolean = false
    }
}
