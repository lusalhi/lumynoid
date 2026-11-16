package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ProviderConfigStore
import dev.lusalhi.lumynoid.core.SecretPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The decorator the rest of the app sees as [LlmPort] (AD-12). It is a pass-through for the one active
 * provider and model, read on every call so a switch applies from the next message; rotation and model
 * filtering arrive with epic-hemat-kuota.
 */
class LlmRouter(
    private val adapters: ProviderAdapters,
    private val configs: ProviderConfigStore,
    private val secrets: SecretPort,
) : LlmPort {

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        val active = configs.active()
        val provider = active?.let { configs.provider(it.providerId) }
        val apiKey = provider?.let { secrets.get(it.apiKey) }
        if (active == null || provider == null || apiKey.isNullOrBlank()) {
            emit(LlmChunk.Failed(LlmError.AuthFailed))
            return@flow
        }
        emit(LlmChunk.Routed(provider.id, active.modelId))
        emitAll(adapters.forKind(provider.kind).stream(provider.baseUrl, active.modelId, apiKey, request))
    }
}
