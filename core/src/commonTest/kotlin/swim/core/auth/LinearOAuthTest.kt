package swim.core.auth

import kotlinx.coroutines.test.runTest
import swim.core.Canned
import swim.core.HttpRecorder
import swim.core.model.AuthError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinearOAuthTest {
    @Test
    fun authorizeUrlCarriesPkceAndTheUserActor() {
        val oauth = LinearOAuth(HttpRecorder(Canned("{}")).client, "client-1", "http://127.0.0.1:47821/callback")
        val url = oauth.authorizeUrl(codeChallenge = "challenge-1", state = "state-1")

        assertTrue(url.startsWith("https://linear.app/oauth/authorize?"))
        assertContains(url, "client_id=client-1")
        assertContains(url, "response_type=code")
        assertContains(url, "code_challenge=challenge-1")
        assertContains(url, "code_challenge_method=S256")
        assertContains(url, "state=state-1")
        assertContains(url, "actor=user")
    }

    @Test
    fun codeExchangeReturnsTokensThatExpire() = runTest {
        val recorder = HttpRecorder(
            Canned("""{"access_token":"lin_at","refresh_token":"lin_rt","expires_in":86400,"token_type":"Bearer"}""")
        )
        val oauth = LinearOAuth(recorder.client, "client-1", "http://127.0.0.1:47821/callback")

        val tokens = oauth.exchangeCode("code-1", "verifier-1")

        assertEquals("lin_at", tokens.accessToken)
        assertEquals("lin_rt", tokens.refreshToken)
        assertTrue(!tokens.isExpired())
        assertContains(recorder.bodies[0], "code_verifier=verifier-1")
        assertContains(recorder.bodies[0], "grant_type=authorization_code")
    }

    @Test
    fun aRejectedExchangeReportsLinearsReason() = runTest {
        val recorder = HttpRecorder(
            Canned(
                """{"error":"invalid_grant","error_description":"The code is spent"}""",
                io.ktor.http.HttpStatusCode.BadRequest,
            )
        )
        val oauth = LinearOAuth(recorder.client, "client-1", "http://127.0.0.1:47821/callback")
        val error = assertFailsWith<AuthError> { oauth.exchangeCode("code-1", "verifier-1") }
        assertContains(error.message.orEmpty(), "The code is spent")
    }
}
