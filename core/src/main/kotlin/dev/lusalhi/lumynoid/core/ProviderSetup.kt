package dev.lusalhi.lumynoid.core

/** What "Connect and start" on the first-run form came to. */
sealed interface ConnectOutcome {
    /** The connection works and the provider is saved. */
    data object Started : ConnectOutcome

    /** The test failed; nothing was saved. */
    data class TestFailed(val error: LlmError) : ConnectOutcome

    /** URL, key or model is missing, so nothing was sent or saved. */
    data object Incomplete : ConnectOutcome
}

/**
 * Tests [draft] and saves it only when the test passes, so a first run never ends with a provider that does not answer.
 * "Save anyway" is a plain [ProviderAdminPort.save].
 */
suspend fun connectAndSave(admin: ProviderAdminPort, draft: ProviderDraft): ConnectOutcome =
    when (val test = admin.testConnection(draft)) {
        ConnectionTestResult.Success -> when (admin.save(draft)) {
            ProviderSaveResult.SAVED -> ConnectOutcome.Started
            ProviderSaveResult.INCOMPLETE -> ConnectOutcome.Incomplete
        }
        is ConnectionTestResult.Failure -> ConnectOutcome.TestFailed(test.error)
        ConnectionTestResult.Incomplete -> ConnectOutcome.Incomplete
    }
