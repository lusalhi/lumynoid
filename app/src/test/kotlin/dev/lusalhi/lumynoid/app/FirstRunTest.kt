package dev.lusalhi.lumynoid.app

import dev.lusalhi.lumynoid.core.ProviderPresets
import dev.lusalhi.lumynoid.core.ProviderSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunTest {
    private val saved = ProviderSummary("p1", "OpenRouter", "openrouter", "https://openrouter.ai/api/v1", listOf("m"))

    @Test fun firstRunFormPreselectsOpenRouterAndStaysActive() {
        val preset = ProviderPresets.all.first()
        val form = firstRunForm()
        assertEquals("openrouter", form.presetId)
        assertEquals(preset.name, form.name)
        assertEquals(preset.baseUrl, form.baseUrl)
        assertEquals(preset.kind, form.kind)
        assertTrue(form.makeActive)
        assertEquals("", form.apiKey)
        assertEquals("", form.model)
        assertEquals(null, form.id)
    }

    @Test fun isFirstRunOnlyForANewFormWithNoSavedProvider() {
        assertTrue(ProvidersState(form = firstRunForm()).isFirstRun)
        assertFalse(ProvidersState(form = null).isFirstRun)
        assertFalse(ProvidersState(form = firstRunForm().copy(id = "p1")).isFirstRun)
        assertFalse(ProvidersState(providers = listOf(saved), form = firstRunForm()).isFirstRun)
    }

    @Test fun aKeyWithoutAModelIsIncompleteButCanListModels() {
        val form = firstRunForm().copy(apiKey = "k")
        assertFalse(form.complete)
        assertTrue(form.modelMissing)
        assertTrue(form.canListModels)
    }
}
