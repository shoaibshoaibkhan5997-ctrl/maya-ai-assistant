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

/**
 * Executes ONLY the allowlisted [AssistantCommand] types, and only while the user has
 * turned Screen Control ON inside MAYA. It never sends screen contents anywhere; it only
 * looks at node structure locally to find a button, a list item or the focused input field.
 * Payment-like labels and bank/wallet apps are refused.
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

    private fun tapFirstResult(): ActionResult {
        val root = rootInActiveWindow ?: return noScreen()
        if (blockedScreen(root)) return blockedResult()
        val lists = collect(root) { it.isScrollable && it.isVisibleToUser }
        val container = lists.maxByOrNull { area(it) }
            ?: return ActionResult(false, "Screen par result ki list nahi mili.")
        val dm = resources.displayMetrics
        val minH = dm.heightPixels / 10
        val minW = dm.widthPixels / 2
        val item = dfsFirst(container) { n ->
            val r = Rect()
            n.getBoundsInScreen(r)
            !n.isScrollable && n.isClickable && n.isEnabled && n.isVisibleToUser && !n.isEditable &&
                r.height() >= minH && r.width() >= minW && !AD_LABEL.containsMatchIn(labelOf(n))
        } ?: return ActionResult(false, "List mein tap karne layak pehla result nahi mila.")
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
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field == null || !field.isEditable) {
            return ActionResult(false, "Koi focused text box nahi mila. Pehle text box par tap karein.")
        }
        if (field.isPassword) {
            return ActionResult(false, "Password field mein MAYA text nahi likhti.")
        }
        val existing = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            ActionResult(true, "Text text box mein likh diya gaya.")
        else ActionResult(false, "Yeh text box text set karne nahi deta.")
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

        private val _connected = MutableStateFlow(false)
        /** True only while Android has the service enabled and bound. */
        val connected: StateFlow<Boolean> = _connected

        @Volatile var instance: MayaAccessibilityService? = null
            private set

        /** Mirrors MAYA's in-app Screen Control switch. Default OFF. */
        @Volatile var acceptingCommands: Boolean = false
    }
}
