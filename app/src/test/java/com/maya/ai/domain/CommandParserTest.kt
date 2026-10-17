package com.maya.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    @Test fun answer() {
        val c = CommandParser.parse("""{"type":"ANSWER_QUESTION","reply":"Namaste"}""")
        assertEquals(AssistantCommand.AnswerQuestion("Namaste"), c)
        assertFalse(c.isDeviceAction)
    }

    @Test fun openApp() {
        val c = CommandParser.parse("""{"type":"OPEN_APP","app":"YouTube","reply":"ok"}""")
        assertEquals(AssistantCommand.OpenApp("YouTube"), c)
        assertTrue(c.isDeviceAction)
        assertFalse(c.requiresConfirmation)
    }

    @Test fun scrollValidDirection() {
        assertEquals(AssistantCommand.Scroll(ScrollDirection.DOWN), CommandParser.parse("""{"type":"scroll","direction":"down"}"""))
    }

    @Test fun scrollInvalidDirectionRejected() {
        assertTrue(CommandParser.parse("""{"type":"SCROLL","direction":"SIDEWAYS"}""") is AssistantCommand.Invalid)
    }

    @Test fun typeTextNeedsConfirmation() {
        val c = CommandParser.parse("""{"type":"TYPE_TEXT","text":"hello"}""")
        assertTrue(c.requiresConfirmation)
    }

    @Test fun typeTextTooLongRejected() {
        val long = "a".repeat(CommandParser.MAX_TEXT + 1)
        assertTrue(CommandParser.parse("""{"type":"TYPE_TEXT","text":"$long"}""") is AssistantCommand.Invalid)
    }

    @Test fun unknownTypeRejected() {
        assertTrue(CommandParser.parse("""{"type":"RUN_SHELL","text":"rm -rf /"}""") is AssistantCommand.Invalid)
    }

    @Test fun plainTextIsNeverADeviceAction() {
        val c = CommandParser.parse("YouTube kholo")
        assertFalse(c.isDeviceAction)
    }

    @Test fun codeFenceStripped() {
        val c = CommandParser.parse("```json\n{\"type\":\"NAVIGATE_BACK\"}\n```")
        assertEquals(AssistantCommand.NavigateBack, c)
    }

    @Test fun missingAppNameRejected() {
        assertTrue(CommandParser.parse("""{"type":"OPEN_APP","app":""}""") is AssistantCommand.Invalid)
    }

    @Test fun stopActions() {
        assertEquals(AssistantCommand.StopActions, CommandParser.parse("""{"type":"STOP_ACTIONS"}"""))
    }
}
