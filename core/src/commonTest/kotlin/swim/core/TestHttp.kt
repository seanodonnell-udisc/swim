package swim.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent

/** One canned HTTP answer. The last one repeats if the code under test asks again. */
data class Canned(
    val body: String,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val headers: Headers = Headers.Empty,
)

/** A Ktor client that answers from a script and remembers what it was asked. */
class HttpRecorder(private val responses: List<Canned>) {
    constructor(vararg responses: Canned) : this(responses.toList())

    val requests: MutableList<HttpRequestData> = mutableListOf()
    val bodies: MutableList<String> = mutableListOf()

    private var index = 0

    val client: HttpClient = HttpClient(
        MockEngine { request ->
            requests += request
            bodies += request.body.asText()
            val canned = responses[minOf(index, responses.size - 1)]
            index++
            respond(canned.body, canned.status, canned.headers)
        }
    )
}

private suspend fun OutgoingContent.asText(): String = when (this) {
    is TextContent -> text
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    else -> ""
}
