package swim.core.url

import kotlinx.coroutines.test.runTest
import swim.core.Canned
import swim.core.HttpRecorder
import swim.core.linear.LinearClient
import swim.core.linear.apiKeyAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val PROJECTS = """{"data":{"projects":{"nodes":[
  {"id":"5a1c0ffee1234abcd5678","name":"Core Platform","state":"started"},
  {"id":"9b2d0deadbeef9876543","name":"Mobile","state":"planned"}
]}}}"""

private fun linear(vararg responses: Canned) =
    LinearClient(HttpRecorder(responses.toList()).client, apiKeyAuth("lin_api_key"))

class ResolveLinearUrlTest {
    // Deviation from the original: the resolved project travels as an id, so two projects with
    // overlapping names cannot collide when the filter is applied.
    @Test
    fun aProjectUrlCarriesTheProjectIdAndName() = runTest {
        val resolved = resolveLinearUrl(
            "https://linear.app/acme/project/core-platform-0ffee1234abc",
            linear(Canned(PROJECTS)),
        )

        assertEquals("Core Platform", resolved.filters.project)
        assertEquals("5a1c0ffee1234abcd5678", resolved.filters.projectId)
        assertEquals("/acme/project/core-platform-0ffee1234abc", resolved.urlSource)
    }

    @Test
    fun aTeamUrlWithQueryParametersBecomesFilters() = runTest {
        val resolved = resolveLinearUrl(
            "https://linear.app/acme/team/ENG/active?priority=2&assignee=ada",
            linear(Canned(PROJECTS)),
        )

        assertEquals("ENG", resolved.filters.team)
        assertEquals(2, resolved.filters.priority)
        assertEquals("ada", resolved.filters.assignee)
        assertNull(resolved.singleIssueId)
        assertEquals("/acme/team/ENG/active?priority=2&assignee=ada", resolved.urlSource)
    }

    @Test
    fun anIssueUrlKeepsTheTeamAndNamesTheIssue() = runTest {
        val resolved = resolveLinearUrl("https://linear.app/acme/issue/eng-42/some-title", linear())

        assertEquals("ENG-42", resolved.singleIssueId)
        assertEquals("ENG", resolved.filters.team)
    }
}
