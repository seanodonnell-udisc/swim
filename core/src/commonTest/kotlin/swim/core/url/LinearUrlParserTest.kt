package swim.core.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinearUrlParserTest {
    private fun parse(url: String): ParsedLinearUrl = (parseLinearUrl(url) as ParseResult.Success).data
    private fun fail(url: String): String = (parseLinearUrl(url) as ParseResult.Failure).error

    @Test
    fun parsesAnIssueUrl() {
        val data = parse("https://linear.app/acme/issue/mob-123/some-slug")
        assertEquals(LinearUrlType.ISSUE, data.type)
        assertEquals("MOB-123", data.issueIdentifier)
        assertEquals("MOB", data.teamKey)
    }

    @Test
    fun parsesAProjectUrlWithATrailingUuidFragment() {
        val data = parse("https://linear.app/acme/project/big-launch-1234567890ab")
        assertEquals(LinearUrlType.PROJECT, data.type)
        assertEquals("1234567890ab", data.projectId)
        assertEquals("big-launch", data.projectSlug)
    }

    @Test
    fun parsesAProjectUrlWithoutAUuidFragment() {
        val data = parse("https://linear.app/acme/project/big-launch")
        assertEquals(LinearUrlType.PROJECT, data.type)
        assertEquals(null, data.projectId)
        assertEquals("big-launch", data.projectSlug)
    }

    @Test
    fun parsesATeamUrl() {
        val data = parse("https://linear.app/acme/team/MOB")
        assertEquals(LinearUrlType.TEAM, data.type)
        assertEquals("MOB", data.teamKey)
        assertEquals(null, data.queryParams)
    }

    @Test
    fun parsesATeamCycleUrl() {
        val cycleId = "123e4567-e89b-12d3-a456-426614174000"
        val data = parse("https://linear.app/acme/team/MOB/cycle/$cycleId")
        assertEquals(LinearUrlType.CYCLE, data.type)
        assertEquals("MOB", data.teamKey)
        assertEquals(cycleId, data.cycleId)
    }

    @Test
    fun aTeamUrlWithQueryParamsIsFiltered() {
        val data = parse("https://linear.app/acme/team/MOB?state=Todo")
        assertEquals(LinearUrlType.FILTERED, data.type)
        assertEquals("Todo", data.queryParams?.state)
    }

    @Test
    fun viewUrlsAreRejected() {
        assertEquals("Custom views cannot be queried through the API", fail("https://linear.app/acme/view/abc123"))
    }

    @Test
    fun nonLinearHostsAreRejected() {
        assertEquals("Not a Linear URL", fail("https://example.com/acme/team/MOB"))
    }

    @Test
    fun unrecognizedKindsAreRejected() {
        val error = fail("https://linear.app/acme/settings/profile")
        assertEquals("Unrecognized Linear URL. Supported: issue, team, team cycle, and project URLs.", error)
    }

    @Test
    fun aMissingSchemeIsTreatedAsHttps() {
        val data = parse("linear.app/acme/team/MOB")
        assertEquals(LinearUrlType.TEAM, data.type)
        assertEquals("MOB", data.teamKey)
    }

    @Test
    fun priorityQueryParamIsKeptOnlyInsideZeroToFour() {
        assertEquals(0, parse("https://linear.app/acme/team/MOB?priority=0").queryParams?.priority)
        assertEquals(4, parse("https://linear.app/acme/team/MOB?priority=4").queryParams?.priority)
        assertEquals(null, parse("https://linear.app/acme/team/MOB?priority=5").queryParams?.priority)
        assertEquals(null, parse("https://linear.app/acme/team/MOB?priority=-1").queryParams?.priority)
    }

    @Test
    fun isLinearUrlDetectsLinearHosts() {
        assertTrue(isLinearUrl("https://linear.app/acme/issue/MOB-1"))
        assertTrue(isLinearUrl("linear.app/acme/issue/MOB-1"))
        assertFalse(isLinearUrl("https://example.com"))
    }
}
