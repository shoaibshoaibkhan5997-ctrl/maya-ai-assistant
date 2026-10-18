package com.maya.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberParserTest {
    @Test fun tenDigitGetsIndiaCode() {
        assertEquals("+919876543210", CommandParser.normalizeNumber("98765 43210"))
    }

    @Test fun plusNumberKept() {
        assertEquals("+919876543210", CommandParser.normalizeNumber("+91 98765-43210"))
    }

    @Test fun leadingZeroRemoved() {
        assertEquals("+919876543210", CommandParser.normalizeNumber("09876543210"))
    }

    @Test fun doubleZeroPrefix() {
        assertEquals("+919876543210", CommandParser.normalizeNumber("00919876543210"))
    }

    @Test fun emergencyAndShortNumbersRejected() {
        assertNull(CommandParser.normalizeNumber("112"))
        assertNull(CommandParser.normalizeNumber("100"))
    }

    @Test fun lettersRejected() {
        assertNull(CommandParser.normalizeNumber("abc123"))
    }

    @Test fun callParsed() {
        val c = CommandParser.parse("""{"type":"CALL","number":"9876543210"}""")
        assertEquals(AssistantCommand.Call("+919876543210"), c)
        assertTrue(c.requiresConfirmation)
    }

    @Test fun whatsappNeedsMessage() {
        val c = CommandParser.parse("""{"type":"WHATSAPP_MESSAGE","number":"9876543210","text":""}""")
        assertTrue(c is AssistantCommand.Invalid)
    }

    @Test fun messageNotAllowedInsidePlan() {
        val c = CommandParser.parse(
            """{"type":"PLAN","steps":[{"type":"CALL","number":"9876543210"}]}"""
        )
        assertTrue(c is AssistantCommand.Invalid)
    }
}
