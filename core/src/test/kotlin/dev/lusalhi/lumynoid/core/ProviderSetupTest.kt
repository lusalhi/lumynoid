package dev.lusalhi.lumynoid.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeAdmin(
    private val test: ConnectionTestResult,
    private val save: ProviderSaveResult = ProviderSaveResult.SAVED,
) : ProviderAdminPort {
    val tested = mutableListOf<ProviderDraft>()
    val saved = mutableListOf<ProviderDraft>()

    override suspend fun overview(): ProviderOverview = error("not used")
    override suspend fun save(draft: ProviderDraft): ProviderSaveResult { saved += draft; return save }
    override suspend fun listModels(draft: ProviderDraft): ModelListResult = error("not used")
    override suspend fun testConnection(draft: ProviderDraft): ConnectionTestResult { tested += draft; return test }
    override suspend fun select(providerId: String, modelId: String) = error("not used")
}

class ProviderSetupTest {
    private val draft = ProviderDraft(null, "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "key", "some/model", makeActive = true)

    @Test
    fun successSavesTheTestedDraft() = runTest {
        val admin = FakeAdmin(ConnectionTestResult.Success)
        assertEquals(ConnectOutcome.Started, connectAndSave(admin, draft))
        assertEquals(listOf(draft), admin.tested)
        assertEquals(listOf(draft), admin.saved)
    }

    @Test
    fun failureNeverSavesAndCarriesTheReason() = runTest {
        val admin = FakeAdmin(ConnectionTestResult.Failure(LlmError.AuthFailed))
        assertEquals(ConnectOutcome.TestFailed(LlmError.AuthFailed), connectAndSave(admin, draft))
        assertTrue(admin.saved.isEmpty())
    }

    @Test
    fun incompleteNeverSaves() = runTest {
        val admin = FakeAdmin(ConnectionTestResult.Incomplete)
        assertEquals(ConnectOutcome.Incomplete, connectAndSave(admin, draft))
        assertTrue(admin.saved.isEmpty())
    }

    @Test
    fun aSaveThatComesBackIncompleteIsNotReportedAsStarted() = runTest {
        val admin = FakeAdmin(ConnectionTestResult.Success, ProviderSaveResult.INCOMPLETE)
        assertEquals(ConnectOutcome.Incomplete, connectAndSave(admin, draft))
    }
}
