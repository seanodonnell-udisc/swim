@file:OptIn(ExperimentalTime::class)

package swim.core.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import swim.core.model.AuthError
import swim.core.model.NetworkError
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Linear OAuth 2.0 authorization code flow with PKCE. Public client: no secret.
 * The client id is a parameter because registration is per-distribution.
 */
class LinearOAuth(
    private val http: HttpClient,
    private val clientId: String,
    private val redirectUri: String,
) {
    /** The URL to open in the browser. Pass `Pkce.challenge(verifier)` and a `Pkce.createState()`. */
    fun authorizeUrl(codeChallenge: String, state: String, scope: String = DEFAULT_SCOPE): String =
        URLBuilder(AUTHORIZE_URL).apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", scope)
            parameters.append("state", state)
            parameters.append("actor", "user")
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

    /** Trades the callback code for tokens. */
    suspend fun exchangeCode(code: String, codeVerifier: String): LinearTokens = tokenRequest(
        Parameters.build {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
            append("client_id", clientId)
            append("code_verifier", codeVerifier)
        }
    )

    /** Renews an expired access token. Linear allows a replayed refresh for 30 minutes. */
    suspend fun refresh(refreshToken: String): LinearTokens = tokenRequest(
        Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", clientId)
        }
    )

    /** Revokes a token. Sign-out must call this (App Store guideline 5.1.1(v)). */
    suspend fun revoke(accessToken: String) {
        val response = request { http.post(REVOKE_URL) { header(HttpHeaders.Authorization, "Bearer $accessToken") } }
        if (!response.status.isSuccess()) {
            throw AuthError("Linear rejected the revoke request: ${response.status}")
        }
    }

    private suspend fun tokenRequest(form: Parameters): LinearTokens {
        val response = request { http.submitForm(TOKEN_URL, form) }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AuthError("Linear sign-in failed: ${errorMessage(body) ?: response.status.toString()}")
        }
        val decoded = try {
            oauthJson.decodeFromString(LinearTokenResponse.serializer(), body)
        } catch (e: Exception) {
            throw AuthError("Linear returned an unreadable token response")
        }
        return LinearTokens(
            accessToken = decoded.accessToken,
            refreshToken = decoded.refreshToken,
            expiresAt = Clock.System.now() + decoded.expiresIn.seconds,
        )
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NetworkError("Could not reach Linear: ${e.message}", e)
    }

    private fun errorMessage(body: String): String? = try {
        oauthJson.decodeFromString(LinearErrorResponse.serializer(), body).let {
            it.errorDescription ?: it.error
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val AUTHORIZE_URL: String = "https://linear.app/oauth/authorize"
        const val TOKEN_URL: String = "https://api.linear.app/oauth/token"
        const val REVOKE_URL: String = "https://api.linear.app/oauth/revoke"

        /** Read covers issues and relations; write covers assignee and relation mutations. */
        const val DEFAULT_SCOPE: String = "read,write"
    }
}

private val oauthJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class LinearTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 86_400,
)

@Serializable
private data class LinearErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
