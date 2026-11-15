package dev.lusalhi.lumynoid.llm

import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.ProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** One wire format (AD-11): how to stream an answer and how to list models. Failures are typed, never thrown. */
interface WireAdapter {
    fun stream(baseUrl: String, model: String, apiKey: String, request: LlmRequest): Flow<LlmChunk>

    suspend fun models(baseUrl: String, apiKey: String): ModelListResult
}

/** The adapter for each [ProviderKind], sharing one HTTP client. */
class ProviderAdapters(client: HttpClient) {
    private val openAi = OpenAiCompatibleAdapter(client)
    private val anthropic = AnthropicAdapter(client)

    fun forKind(kind: ProviderKind): WireAdapter = when (kind) {
        ProviderKind.OPENAI_COMPATIBLE -> openAi
        ProviderKind.ANTHROPIC -> anthropic
    }
}

internal val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** HTTP statuses both wire formats report the same way. */
internal fun HttpResponse.toLlmError(): LlmError = when (status) {
    HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> LlmError.AuthFailed
    HttpStatusCode.PaymentRequired -> LlmError.QuotaExhausted
    HttpStatusCode.TooManyRequests ->
        LlmError.RateLimited(headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000))
    else -> LlmError.Server
}

@Serializable
private class ModelList(val data: List<ModelEntry> = emptyList())

@Serializable
private class ModelEntry(val id: String)

/**
 * `GET [url]` with the caller's auth headers, reading `data[].id`. Any failure, an unreadable body or an
 * empty list is [ModelListResult.Unavailable] so the caller can fall back to typing the model.
 */
internal suspend fun HttpClient.listModelIds(url: String, auth: HttpRequestBuilder.() -> Unit): ModelListResult = try {
    val response = get(url) {
        auth()
        accept(ContentType.Application.Json)
    }
    if (!response.status.isSuccess()) {
        ModelListResult.Unavailable(response.toLlmError())
    } else {
        val ids = wireJson.decodeFromString(ModelList.serializer(), response.bodyAsText()).data.map { it.id }.distinct()
        if (ids.isEmpty()) ModelListResult.Unavailable(null) else ModelListResult.Models(ids)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ModelListResult.Unavailable(LlmError.Network)
} catch (e: Exception) {
    ModelListResult.Unavailable(LlmError.Server)
}
