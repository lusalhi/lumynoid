package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.ProviderAdminPort
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderInput
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.UuidV7

/**
 * Saves the one active provider. The key goes to the secret store and only its [SecretRef] is kept
 * with the configuration (AD-15). A blank key on an already-configured provider keeps the old key.
 */
class ProviderAdmin(
    private val configs: ProviderConfigStore,
    private val secrets: SecretPort,
    private val ids: IdGenerator = UuidV7,
) : ProviderAdminPort {

    override suspend fun current(): ProviderInput? =
        configs.active()?.let { ProviderInput(baseUrl = it.baseUrl, apiKey = "", model = it.model) }

    override suspend fun save(input: ProviderInput): ProviderSaveResult {
        val existing = configs.active()
        if (input.baseUrl.isBlank() || input.model.isBlank()) return ProviderSaveResult.INCOMPLETE
        if (input.apiKey.isBlank() && existing == null) return ProviderSaveResult.INCOMPLETE
        val id = existing?.id ?: ids.next()
        val ref = existing?.apiKey ?: SecretRef("provider_key_$id")
        if (input.apiKey.isNotBlank()) secrets.put(ref, input.apiKey.trim())
        configs.save(ProviderConfig(id, withScheme(input.baseUrl), input.model.trim(), ref))
        return ProviderSaveResult.SAVED
    }

    /** People paste `openrouter.ai/api/v1`; without a scheme the request could never be built. */
    private fun withScheme(raw: String): String = raw.trim().let { if ("://" in it) it else "https://$it" }
}
