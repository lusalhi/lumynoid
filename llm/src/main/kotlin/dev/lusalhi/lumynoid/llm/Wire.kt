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
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

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

/** A JSON string literal; numbers, booleans, null, arrays and objects are not coerced. */
internal fun JsonElement?.wireStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** HTTP statuses both wire formats report the same way. */
internal fun HttpResponse.toLlmError(): LlmError = when (status) {
    HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> LlmError.AuthFailed
    HttpStatusCode.PaymentRequired -> LlmError.QuotaExhausted
    HttpStatusCode.TooManyRequests ->
        LlmError.RateLimited(headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000))
    else -> LlmError.Server
}

/** `[baseUrl]` without a trailing slash, followed by [path]. */
internal fun joinUrl(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

/** A short, provider-safe ID derived from the runner's durable ID. */
internal fun toolCallWireId(prefix: String, localId: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(localId.encodeToByteArray())
    val suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
    return prefix + suffix
}

/**
 * POSTs the JSON [body] to [url] and hands a successful response to [readStream]. A failed status, an IO
 * error or any other exception (a decode error inside [readStream] included) becomes a typed
 * [LlmChunk.Failed]. Cancellation and an exception thrown by the downstream collector propagate unchanged.
 * Call it inside the adapter's `flow`.
 */
internal suspend fun FlowCollector<LlmChunk>.streamPost(
    client: HttpClient,
    url: String,
    body: String,
    auth: HttpRequestBuilder.() -> Unit,
    readStream: suspend FlowCollector<LlmChunk>.(HttpResponse) -> Unit,
) {
    // An exception thrown by the downstream collector must pass through untouched: emitting again after it would break
    // Flow's exception transparency and hide the real error.
    var downstream: Throwable? = null
    val guarded = FlowCollector<LlmChunk> { chunk ->
        try {
            emit(chunk)
        } catch (t: Throwable) {
            downstream = t
            throw t
        }
    }
    try {
        client.preparePost(url) {
            auth()
            accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                guarded.emit(LlmChunk.Failed(response.toLlmError()))
            } else {
                guarded.readStream(response)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e === downstream) throw e
        emit(LlmChunk.Failed(if (e is IOException) LlmError.Network else LlmError.Server))
    }
}

/**
 * Reads the server-sent `data:` payloads of [response] one by one, skipping `event:` names, blank
 * separators and comments. [onData] returns false to stop reading.
 */
internal suspend inline fun HttpResponse.readDataLines(onData: (String) -> Boolean) {
    val channel: ByteReadChannel = bodyAsChannel()
    while (true) {
        val line = channel.readLine() ?: break
        if (!line.startsWith("data:")) continue
        if (!onData(line.removePrefix("data:").trim())) break
    }
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
