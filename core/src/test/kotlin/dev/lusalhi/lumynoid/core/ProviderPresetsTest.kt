package dev.lusalhi.lumynoid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderPresetsTest {
    @Test
    fun offersTheAgreedPresets() {
        assertEquals(listOf("openrouter", "groq", "gemini", "openai", "anthropic"), ProviderPresets.all.map { it.id })
    }

    @Test
    fun onlyAnthropicSpeaksItsOwnWireFormat() {
        assertEquals(listOf("anthropic"), ProviderPresets.all.filter { it.kind == ProviderKind.ANTHROPIC }.map { it.id })
    }

    @Test
    fun persistedKindNamesAreStableAndUnknownOnesFallBack() {
        assertEquals("openai_compatible", ProviderKind.OPENAI_COMPATIBLE.wireName)
        assertEquals("anthropic", ProviderKind.ANTHROPIC.wireName)
        assertEquals(ProviderKind.ANTHROPIC, ProviderKind.fromWireName("anthropic"))
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, ProviderKind.fromWireName("from_the_future"))
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, ProviderKind.fromWireName(null))
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
