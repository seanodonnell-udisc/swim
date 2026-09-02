package swim.ui.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import swim.core.auth.LinearAuthMode
import swim.core.auth.LinearTokens
import swim.core.auth.TokenStore
import swim.core.model.AuthError
import swim.core.session.authStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connect step behind the graph's GitHub dialog. A user who signed in to Linear before GitHub
 * existed reaches it from the disabled derive toggle, so it must work with the graph already up.
 */
class GithubConnectTest {

    @Test
    fun aPastedTokenIsCheckedStoredAndEnablesTheToggle() = runTest {
        val asked = mutableListOf<HttpRequestData>()
        val store = FakeTokenStore(linear = LinearTokens("lin_api_key", mode = LinearAuthMode.API_KEY))
        val http = HttpClient(
            MockEngine { request ->
                asked += request
                respond("""{"login":"ada"}""", HttpStatusCode.OK)
            }
        )
        assertFalse(authStatus(store).githubConfigured)

        assertEquals("ada", connectGithub(http, store, "ghp_token"))

        assertEquals(1, asked.size, "the token was stored without a check")
        assertEquals("Bearer ghp_token", asked.single().headers["Authorization"])
        assertEquals("ghp_token", store.getGithub())
        assertTrue(authStatus(store).githubConfigured, "the derive toggle stayed disabled")
    }

    @Test
    fun aRefusedTokenIsNotStored() = runTest {
        val store = FakeTokenStore()
        val http = HttpClient(
            MockEngine { respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
        )

        assertFailsWith<AuthError> { connectGithub(http, store, "ghp_wrong") }

        assertNull(store.getGithub())
    }
}

private class FakeTokenStore(private var linear: LinearTokens? = null) : TokenStore {
    private var github: String? = null

    override fun getLinear(): LinearTokens? = linear
    override fun setLinear(tokens: LinearTokens) {
        linear = tokens
    }

    override fun clearLinear() {
        linear = null
    }

    override fun getGithub(): String? = github
    override fun setGithub(token: String) {
        github = token
    }

    override fun clearGithub() {
        github = null
    }
}
