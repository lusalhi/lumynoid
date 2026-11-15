package dev.lusalhi.lumynoid.app

import android.app.Application
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.ProviderAdminPort
import dev.lusalhi.lumynoid.data.LumynoidData
import dev.lusalhi.lumynoid.llm.LlmRouter
import dev.lusalhi.lumynoid.llm.OpenAiCompatibleAdapter
import dev.lusalhi.lumynoid.llm.ProviderAdmin
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/** Composition root (AD-1): the only place adapters are assembled, by hand and without a DI framework. */
class AppGraph(application: Application) {
    val data = LumynoidData(application)

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
            // No overall request timeout: an answer streams for as long as the provider keeps sending.
        }
    }

    private val adapter = OpenAiCompatibleAdapter(httpClient)

    val llm: LlmPort = LlmRouter(adapter, data.providerConfigs, data.secrets)
    val providerAdmin: ProviderAdminPort = ProviderAdmin(adapter, data.providerConfigs, data.secrets)
}

class LumynoidApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
