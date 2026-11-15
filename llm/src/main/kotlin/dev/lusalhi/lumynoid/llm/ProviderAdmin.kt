package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ConnectionTestResult
import dev.lusalhi.lumynoid.core.IdGenerator
import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmMessage
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.ModelProfile
import dev.lusalhi.lumynoid.core.ProviderAdminPort
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderOverview
import dev.lusalhi.lumynoid.core.ProviderPresets
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.ProviderSummary
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.coroutines.flow.first

/**
 * Saves providers, lists their models and tests connections for the UI (AD-23). Keys go to the secret
 * store and only a [SecretRef] is kept with the configuration (AD-15). A blank key on an
 * already-saved provider means "keep the stored key".
 */
class ProviderAdmin(
    private val adapter: OpenAiCompatibleAdapter,
    private val configs: ProviderConfigStore,
    private val secrets: SecretPort,
    private val ids: IdGenerator = UuidV7,
) : ProviderAdminPort {

    override suspend fun overview(): ProviderOverview = ProviderOverview(
        providers = configs.providers().map { p ->
            ProviderSummary(p.id, p.name, p.presetId, p.baseUrl, configs.models(p.id).map { it.modelId })
        },
        active = configs.active(),
    )

    override suspend fun save(draft: ProviderDraft): ProviderSaveResult {
        val existing = draft.id?.let { configs.provider(it) }
        val model = draft.model.trim()
        if (draft.baseUrl.isBlank() || model.isEmpty()) return ProviderSaveResult.INCOMPLETE
        // No new key and no stored one (a new provider, or a saved one whose secret is gone): nothing to call with.
        if (draft.apiKey.isBlank() && (existing == null || secrets.get(existing.apiKey).isNullOrBlank())) {
            return ProviderSaveResult.INCOMPLETE
        }
        val id = existing?.id ?: ids.next()
        val ref = existing?.apiKey ?: SecretRef("provider_key_$id")
        if (draft.apiKey.isNotBlank()) secrets.put(ref, draft.apiKey.trim())
        val url = withScheme(draft.baseUrl)
        configs.save(ProviderConfig(id, nameFor(draft, url), draft.presetId, url, ref))
        configs.saveModel(ModelProfile(id, model))
        val active = configs.active()
        // The first provider, a request for it, and any edit of the active provider all set the active model.
        if (draft.makeActive || active == null || active.providerId == id) configs.setActive(ActiveModel(id, model))
        return ProviderSaveResult.SAVED
    }

    override suspend fun listModels(draft: ProviderDraft): ModelListResult {
        if (draft.baseUrl.isBlank()) return ModelListResult.Unavailable(null)
        val key = keyFor(draft) ?: return ModelListResult.Unavailable(null)
        return adapter.models(withScheme(draft.baseUrl), key)
    }

    override suspend fun testConnection(draft: ProviderDraft): ConnectionTestResult {
        val model = draft.model.trim()
        if (draft.baseUrl.isBlank() || model.isEmpty()) return ConnectionTestResult.Incomplete
        val key = keyFor(draft) ?: return ConnectionTestResult.Incomplete
        // A real one-word chat request: unlike `/models`, it needs a valid key and a model that exists.
        // The first useful chunk settles it, and `first` cancels the rest of the stream.
        val request = LlmRequest(listOf(LlmMessage(Role.USER, "Reply with one word.")))
        val outcome = adapter.stream(withScheme(draft.baseUrl), model, key, request)
            .first { it is LlmChunk.TextDelta || it is LlmChunk.Done || it is LlmChunk.Failed }
        return when (outcome) {
            is LlmChunk.Failed -> ConnectionTestResult.Failure(outcome.error)
            else -> ConnectionTestResult.Success
        }
    }

    override suspend fun select(providerId: String, modelId: String) {
        configs.setActive(ActiveModel(providerId, modelId))
    }

    private suspend fun keyFor(draft: ProviderDraft): String? =
        draft.apiKey.trim().ifEmpty {
            draft.id?.let { configs.provider(it) }?.let { secrets.get(it.apiKey) }
        }?.takeIf { it.isNotBlank() }

    private fun nameFor(draft: ProviderDraft, url: String): String =
        draft.name.trim().ifEmpty {
            ProviderPresets.all.firstOrNull { it.id == draft.presetId }?.name
                ?: url.substringAfter("://").substringBefore('/')
        }

    /** People paste `openrouter.ai/api/v1`; without a scheme the request could never be built. */
    private fun withScheme(raw: String): String = raw.trim().let { if ("://" in it) it else "https://$it" }
}
