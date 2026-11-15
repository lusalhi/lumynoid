package dev.lusalhi.lumynoid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderPresetsTest {
    @Test
    fun offersTheFourAgreedPresets() {
        assertEquals(listOf("openrouter", "groq", "gemini", "openai"), ProviderPresets.all.map { it.id })
    }

    @Test
    fun everyPresetIsHttpsWithoutATrailingSlashAndIdsAreUnique() {
        ProviderPresets.all.forEach {
            assertTrue(it.baseUrl.startsWith("https://"), it.id)
            assertFalse(it.baseUrl.endsWith("/"), it.id)
        }
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.id }.toSet().size)
    }
}
