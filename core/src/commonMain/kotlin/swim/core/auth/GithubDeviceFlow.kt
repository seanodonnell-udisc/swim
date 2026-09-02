@file:OptIn(ExperimentalTime::class)

package swim.core.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import swim.core.model.AuthError
import swim.core.model.NetworkError
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** What the user must type, and where. Show `userCode` and `verificationUri` and wait. */
data class GithubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/**
 * GitHub OAuth device flow. No secret and no redirect, so it works on every platform.
 * Device flow must be enabled in the OAuth app settings or GitHub answers `device_flow_disabled`.
 */
class GithubDeviceFlow(
    private val http: HttpClient,
    private val clientId: String,
) {
    /** Asks GitHub for a device code and the user code to display. */
    suspend fun requestCode(scope: String = DEFAULT_SCOPE): GithubDeviceCode {
        val body = post(DEVICE_CODE_URL, Parameters.build {
            append("client_id", clientId)
            append("scope", scope)
        })
        val decoded = decode(body, GithubDeviceCodeResponse.serializer())
            ?: throw AuthError("GitHub returned an unreadable device-code response")
        decoded.error?.let { throw AuthError(describe(it, decoded.errorDescription)) }
        val deviceCode = decoded.deviceCode
        val userCode = decoded.userCode
        val verificationUri = decoded.verificationUri
        if (deviceCode == null || userCode == null || verificationUri == null) {
            throw AuthError("GitHub returned an incomplete device-code response")
        }
        return GithubDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            expiresInSeconds = decoded.expiresIn ?: 900,
            intervalSeconds = decoded.interval ?: 5,
        )
    }

    /** Polls until the user approves. Returns the access token, or throws with the reason. */
    suspend fun awaitToken(code: GithubDeviceCode): String {
        var interval = code.intervalSeconds
        val deadline = Clock.System.now() + code.expiresInSeconds.seconds
        while (true) {
            delay(interval.seconds)
            val body = post(TOKEN_URL, Parameters.build {
                append("client_id", clientId)
                append("device_code", code.deviceCode)
                append("grant_type", GRANT_TYPE)
            })
            val decoded = decode(body, GithubTokenResponse.serializer())
                ?: throw AuthError("GitHub returned an unreadable token response")
            decoded.accessToken?.let { return it }
            when (decoded.error) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = decoded.interval ?: (interval + 5)
                null -> throw AuthError("GitHub returned no token and no error")
                else -> throw AuthError(describe(decoded.error, decoded.errorDescription))
            }
            if (Clock.System.now() >= deadline) {
                throw AuthError("The device code expired. Start the GitHub sign-in again.")
            }
        }
    }

    private suspend fun post(url: String, form: Parameters): String {
        val response: HttpResponse = try {
            http.submitForm(url, form) { header("Accept", "application/json") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NetworkError("Could not reach GitHub: ${e.message}", e)
        }
        return response.bodyAsText()
    }

    private fun <T> decode(body: String, serializer: kotlinx.serialization.DeserializationStrategy<T>): T? = try {
        deviceJson.decodeFromString(serializer, body)
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEVICE_CODE_URL: String = "https://github.com/login/device/code"
        const val TOKEN_URL: String = "https://github.com/login/oauth/access_token"
        const val GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"

        /** GitHub has no read-only private scope; reading PRs in private repos needs full `repo`. */
        const val DEFAULT_SCOPE: String = "repo"
    }
}

/** Turns a GitHub error code into something a user can act on. */
internal fun describe(error: String, description: String?): String = when (error) {
    "expired_token" -> "The device code expired. Start the GitHub sign-in again."
    "access_denied" -> "The GitHub sign-in was cancelled."
    "device_flow_disabled" -> "This GitHub OAuth app does not have device flow enabled. Turn it on in the app settings."
    "incorrect_client_credentials" -> "The GitHub client id is wrong."
    "unsupported_grant_type" -> "GitHub rejected the device-flow grant type."
    else -> description ?: error
}

private val deviceJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GithubDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Long? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class GithubTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val interval: Long? = null,
)
