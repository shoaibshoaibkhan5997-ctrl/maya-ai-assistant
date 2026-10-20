package com.maya.ai.overlay

import com.maya.ai.domain.ActionResult
import com.maya.ai.domain.AssistantCommand
import com.maya.ai.service.MayaAccessibilityService
import kotlinx.coroutines.delay

/** Runs already-confirmed steps one by one, with the same pacing as the chat screen. */
object BubbleActionRunner {

    /** Returns true only if every step succeeded. Stops at the first failure. */
    suspend fun run(steps: List<AssistantCommand>, report: (message: String, ok: Boolean) -> Unit): Boolean {
        for ((i, step) in steps.withIndex()) {
            val svc = MayaAccessibilityService.instance
            if (svc == null || !MayaAccessibilityService.acceptingCommands) {
                report("Screen Control band ho gaya, plan ruk gaya.", false)
                return false
            }
            val result = withRetry(svc, step)
            val prefix = if (steps.size > 1) "Step ${i + 1}: " else ""
            report(prefix + result.message, result.success)
            if (!result.success) {
                if (steps.size > 1) report("Plan yahin ruk gaya.", false)
                return false
            }
            if (i < steps.lastIndex) delay(settleMs(step))
        }
        return true
    }

    /** Screens load slowly, so finding a button / search box / Send is retried a few times. */
    private suspend fun withRetry(svc: MayaAccessibilityService, step: AssistantCommand): ActionResult {
        val retry = step is AssistantCommand.SearchInApp || step is AssistantCommand.TapText ||
            step is AssistantCommand.TapFirstResult || step is AssistantCommand.TypeText ||
            step is AssistantCommand.PressSend
        var result = svc.execute(step)
        var tries = 1
        while (retry && !result.success && tries < 4) {
            delay(if (step is AssistantCommand.PressSend) 700L else 1000L)
            result = svc.execute(step)
            tries++
        }
        return result
    }

    private fun settleMs(step: AssistantCommand): Long = when (step) {
        is AssistantCommand.OpenApp, is AssistantCommand.SearchYoutube -> 3000L
        is AssistantCommand.SearchInApp -> 2500L
        is AssistantCommand.TapText, is AssistantCommand.TapFirstResult -> 1500L
        else -> 800L
    }
}
