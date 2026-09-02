package swim.core.github

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import swim.core.Canned
import swim.core.HttpRecorder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val URLS = listOf(
    "https://github.com/acme/app/pull/7",
    "https://github.com/acme/other-repo.js/pull/12",
    "https://example.com/not-a-pr",
)

private const val STATUSES = """{"data":{
  "pr0":{"pullRequest":{"reviewDecision":"APPROVED",
    "commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS"}}}]}}},
  "pr1":{"pullRequest":{"reviewDecision":null,
    "commits":{"nodes":[{"commit":{"statusCheckRollup":null}}]}}}
}}"""

class GithubClientTest {
    @Test
    fun oneBatchedQueryCarriesEveryPullRequestAsVariables() = runTest {
        val recorder = HttpRecorder(Canned(STATUSES))
        val github = GithubClient(recorder.client) { "gho_token" }

        val statuses = github.getPrStatuses(URLS)

        assertEquals(1, recorder.requests.size)
        assertEquals("Bearer gho_token", recorder.requests[0].headers["Authorization"])
        assertEquals("swim", recorder.requests[0].headers["User-Agent"])

        val body = recorder.bodies[0]
        assertContains(body, "pr0: repository(owner: \$owner0, name: \$name0)")
        assertContains(body, "pr1: repository(owner: \$owner1, name: \$name1)")
        assertContains(body, "\"owner0\":\"acme\"")
        assertContains(body, "\"name0\":\"app\"")
        assertContains(body, "\"number0\":7")
        assertContains(body, "\"name1\":\"other-repo.js\"")
        assertContains(body, "\"number1\":12")
        // The URL that is not a pull request never reaches GitHub.
        assertTrue(!body.contains("not-a-pr"))

        assertEquals(2, statuses.size)
        assertEquals("APPROVED", statuses[URLS[0]]?.reviewDecision)
        assertEquals("SUCCESS", statuses[URLS[0]]?.checkState)
        assertEquals(null, statuses[URLS[1]]?.reviewDecision)
        assertEquals(null, statuses[URLS[1]]?.checkState)
    }

    @Test
    fun aRejectedTokenDegradesToNoStatuses() = runTest {
        val recorder = HttpRecorder(Canned("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized))
        val github = GithubClient(recorder.client) { "gho_token" }

        assertTrue(github.getPrStatuses(URLS).isEmpty())
    }

    @Test
    fun noTokenMeansNoRequestAtAll() = runTest {
        val recorder = HttpRecorder(Canned(STATUSES))
        val github = GithubClient(recorder.client) { null }

        assertTrue(github.getPrStatuses(URLS).isEmpty())
        assertEquals(0, recorder.requests.size)
    }

    @Test
    fun forbiddenRepliesNameTheActionTheUserMustTake() {
        assertContains(forbiddenMessage("org-1", ""), "SAML")
        assertContains(
            forbiddenMessage(null, """{"message":"Resource protected by organization SAML enforcement"}"""),
            "SAML",
        )
        assertContains(
            forbiddenMessage(null, """{"message":"OAuth App access restrictions"}"""),
            "organization owner",
        )
        assertContains(forbiddenMessage(null, "{}"), "repo")
    }
}
