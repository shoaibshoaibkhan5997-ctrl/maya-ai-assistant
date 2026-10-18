package com.maya.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
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
 * turned Screen Control ON inside MAYA. It does not read or transmit screen contents;
 * it only looks at node structure to find a scrollable container or the focused input field.
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
            is AssistantCommand.Scroll -> scroll(cmd.direction)
            AssistantCommand.NavigateBack ->
                if (performGlobalAction(GLOBAL_ACTION_BACK)) ActionResult(true, "Back action bhej diya gaya.")
                else ActionResult(false, "Back action Android ne accept nahi kiya.")
            is AssistantCommand.TypeText -> typeText(cmd.text)
            else -> ActionResult(false, "Yeh command device action nahi hai.")
        }
    }

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

    /** Opens the YouTube search results page. It cannot play a video; the user picks one. */
    private fun searchYoutube(query: String): ActionResult {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(Intent(base).setPackage("com.google.android.youtube"))
            ActionResult(true, "YouTube par \"$query\" ka search kholne ka request bhej diya gaya. Video aap khud chunein.")
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

    private fun scroll(direction: ScrollDirection): ActionResult {
        val root = rootInActiveWindow ?: return ActionResult(false, "Abhi screen ki jaankari available nahi hai.")
        val target = findFirst(root) { it.isScrollable }
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
        val root = rootInActiveWindow ?: return ActionResult(false, "Abhi screen ki jaankari available nahi hai.")
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

    private fun findFirst(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 2000) {
            val n = queue.removeFirst()
            visited++
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        /** True only while Android has the service enabled and bound. */
        val connected: StateFlow<Boolean> = _connected

        @Volatile var instance: MayaAccessibilityService? = null
            private set

        /** Mirrors MAYA's in-app Screen Control switch. Default OFF. */
        @Volatile var acceptingCommands: Boolean = false
    }
}
