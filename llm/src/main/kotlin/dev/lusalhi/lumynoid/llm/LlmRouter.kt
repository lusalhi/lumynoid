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
 * The decorator the rest of the app sees as [LlmPort] (AD-12). In this story it is a pass-through
 * for the one active provider; rotation and model filtering arrive with epic-hemat-kuota.
 */
class LlmRouter(
    private val adapter: OpenAiCompatibleAdapter,
    private val configs: ProviderConfigStore,
    private val secrets: SecretPort,
) : LlmPort {

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        val config = configs.active()
        val apiKey = config?.let { secrets.get(it.apiKey) }
        if (config == null || apiKey.isNullOrBlank()) {
            emit(LlmChunk.Failed(LlmError.AuthFailed))
            return@flow
        }
        emit(LlmChunk.Routed(config.id, config.model))
        emitAll(adapter.stream(config, apiKey, request))
    }
}
