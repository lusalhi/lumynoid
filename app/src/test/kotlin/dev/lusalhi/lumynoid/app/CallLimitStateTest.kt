package dev.lusalhi.lumynoid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLimitStateTest {
    @Test fun refreshingAnUneditedFieldShowsTheLatestLimit() {
        val refreshed = CallLimitState(20).withSavedLimit(12)
        assertEquals("12", refreshed.input)
        assertFalse(refreshed.canSave)
        assertTrue(refreshed.copy(input = "20").canSave)
    }

    @Test fun refreshingKeepsTheEditedValueAndUsesTheLatestLimitForValidation() {
        val edited = CallLimitState(20, input = "12")
        val refreshed = edited.withSavedLimit(12)
        assertEquals("12", refreshed.input)
        assertEquals(12, refreshed.savedLimit)
        assertFalse(refreshed.canSave)
    }

    @Test fun refreshingKeepsAnInFlightSave() {
        val saving = CallLimitState(20, input = "8", saving = true)
        assertEquals(saving, saving.withSavedLimit(12))
    }
}
