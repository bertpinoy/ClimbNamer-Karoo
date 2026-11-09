package io.github.bertpinoy.climbnamer.naming

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

data class HttpResult(val status: Int, val body: String?, val error: String?) {
    val ok: Boolean get() = error == null && status in 200..299
}

/**
 * HTTP through the Karoo's own connection (Wi-Fi or the companion app).
 * Responses are limited to 100 KB by the Karoo system.
 */
suspend fun KarooSystemService.http(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    timeoutMs: Long = 90_000,
): HttpResult = withTimeout(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        var consumerId: String? = null
        consumerId = addConsumer<OnHttpResponse>(
            OnHttpResponse.MakeHttpRequest(method, url, headers, body, waitForConnection = true),
            onError = { msg ->
                if (cont.isActive) cont.resume(HttpResult(0, null, msg))
            },
        ) { event ->
            val state = event.state
            if (state is HttpResponseState.Complete && cont.isActive) {
                consumerId?.let { removeConsumer(it) }
                cont.resume(HttpResult(state.statusCode, state.body?.decodeToString(), state.error))
            }
        }
        cont.invokeOnCancellation { consumerId?.let { removeConsumer(it) } }
    }
}
