package swim.core.github

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import swim.core.model.AuthError
import swim.core.model.PrStatus

/** One pull request named by a Linear attachment URL. */
data class PrRef(val url: String, val owner: String, val name: String, val number: Int)

/**
 * GitHub's GraphQL API. Swim uses it only to show the status of a pull request. The token is
 * optional. With no token the status is empty; a failure answers null, so the surface can tell
 * "GitHub did not answer" apart from "these pull requests have no reviews and no checks".
 */
class GithubClient(
    private val http: HttpClient,
    private val log: (String) -> Unit = {},
    private val token: suspend () -> String?,
) {
    /**
     * The review decision and the check status for a batch of pull requests, in one request,
     * or null when GitHub could not answer. The values travel as GraphQL variables. Swim does
     * not put them in the query text, because an attacker can control a repository name.
     */
    suspend fun getPrStatuses(urls: List<String>): Map<String, PrStatus>? {
        val accessToken = token() ?: return emptyMap()
        val refs = urls.mapNotNull(::parsePrUrl)
        if (refs.isEmpty()) return emptyMap()

        return try {
            val response = http.post(GRAPHQL_URL) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.UserAgent, USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(githubJson.encodeToString(GraphQlRequest.serializer(), batchedQuery(refs)))
            }
            if (!response.status.isSuccess()) {
                log("github: pull-request status answered ${response.status}")
                return null
            }
            val statuses = readStatuses(refs, response.bodyAsText())
            if (statuses == null) log("github: the pull-request status body carried no data object")
            statuses
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("github: the pull-request status request failed: ${e.message}")
            null
        }
    }

    /**
     * Confirms the token works and returns the login. Org approval and SAML enforcement both
     * answer 403 and both need the user to do something specific, so they get their own messages.
     */
    suspend fun verifyToken(): String {
        val accessToken = token() ?: throw AuthError("No GitHub token. Run `swim auth` to connect GitHub.")
        val response = http.get(USER_URL) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (response.status == HttpStatusCode.Forbidden) {
            throw AuthError(forbiddenMessage(response.headers["X-GitHub-SSO"], response.bodyAsText()))
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthError("GitHub rejected the token. Run `swim auth` to connect GitHub again.")
        }
        if (!response.status.isSuccess()) {
            throw AuthError("GitHub returned ${response.status}.")
        }
        return try {
            githubJson.decodeFromString(GithubUser.serializer(), response.bodyAsText()).login
        } catch (e: Exception) {
            throw AuthError("GitHub returned an unreadable user response.")
        }
    }
}

/** The message for a 403: SAML single sign-on and org access restrictions need different actions. */
internal fun forbiddenMessage(ssoHeader: String?, body: String): String = when {
    ssoHeader != null || body.contains("SAML", ignoreCase = true) ->
        "This organization enforces SAML single sign-on. Authorize the token for the organization, then try again."
    body.contains("OAuth App access restrictions", ignoreCase = true) || body.contains("not authorized", ignoreCase = true) ->
        "This organization restricts OAuth apps. Ask an organization owner to approve Swim, then try again."
    else -> "GitHub refused the request. The token may be missing the `repo` scope."
}

private fun batchedQuery(refs: List<PrRef>): GraphQlRequest {
    val declarations = refs.indices.joinToString(", ") { i ->
        "\$owner$i: String!, \$name$i: String!, \$number$i: Int!"
    }
    val selections = refs.indices.joinToString("\n") { i ->
        """  pr$i: repository(owner: ${'$'}owner$i, name: ${'$'}name$i) {
    pullRequest(number: ${'$'}number$i) {
      reviewDecision
      commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }
    }
  }"""
    }
    val variables = buildJsonObject {
        refs.forEachIndexed { i, ref ->
            put("owner$i", ref.owner)
            put("name$i", ref.name)
            put("number$i", ref.number)
        }
    }
    return GraphQlRequest("query PrStatuses($declarations) {\n$selections\n}", variables)
}

private fun readStatuses(refs: List<PrRef>, body: String): Map<String, PrStatus>? {
    val data = (githubJson.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
        ?: return null
    val out = mutableMapOf<String, PrStatus>()
    refs.forEachIndexed { i, ref ->
        val pr = (data["pr$i"] as? JsonObject)?.get("pullRequest") as? JsonObject ?: return@forEachIndexed
        val rollup = ((pr["commits"] as? JsonObject)?.get("nodes") as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("commit") as? JsonObject }
            ?.let { it["statusCheckRollup"] as? JsonObject }
        out[ref.url] = PrStatus(
            reviewDecision = pr["reviewDecision"].asString(),
            checkState = rollup?.get("state").asString(),
        )
    }
    return out
}

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

private val PR_URL = Regex("""github\.com/([\w.-]+)/([\w.-]+)/pull/(\d+)""")

/**
 * The one definition of "a GitHub pull-request URL". Null for anything this client cannot ask
 * GitHub about, so a chip is never drawn for a URL that can never carry a status.
 */
fun parsePrUrl(url: String): PrRef? {
    val match = PR_URL.find(url) ?: return null
    val number = match.groupValues[3].toIntOrNull() ?: return null
    return PrRef(url = url, owner = match.groupValues[1], name = match.groupValues[2], number = number)
}

@Serializable
private data class GraphQlRequest(val query: String, val variables: JsonObject)

@Serializable
private data class GithubUser(val login: String = "")

private val githubJson = Json { ignoreUnknownKeys = true }

private const val GRAPHQL_URL = "https://api.github.com/graphql"
private const val USER_URL = "https://api.github.com/user"
private const val USER_AGENT = "swim"
