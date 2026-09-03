@file:OptIn(ExperimentalTime::class)

package swim.core.linear

import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import swim.core.Canned
import swim.core.HttpRecorder
import swim.core.model.ApiError
import swim.core.model.FilterOptions
import swim.core.model.RateLimitedError
import swim.core.model.RelationType
import swim.core.model.WorkflowStateType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val PAGE_ONE = """{"data":{"issues":{"nodes":[
  {"id":"u1","identifier":"ENG-1","title":"One","priority":1,"estimate":3,
   "url":"https://linear.app/w/issue/ENG-1","createdAt":"2026-01-01T00:00:00.000Z",
   "updatedAt":"2026-01-02T00:00:00.000Z","state":{"name":"Todo","type":"unstarted"},
   "team":{"key":"ENG"},"assignee":{"name":"Ada"},"project":{"name":"Core"},
   "projectMilestone":{"id":"pm-1","name":"M2 Clients"},
   "labels":{"nodes":[{"name":"bug"}]},
   "attachments":{"nodes":[{"url":"https://github.com/acme/app/pull/7","title":"Fix it"},
                           {"url":"https://example.com/doc","title":"Doc"}]},
   "relations":{"nodes":[{"id":"r1","type":"blocks",
     "relatedIssue":{"id":"u2","identifier":"ENG-2","title":"Two","state":{"name":"Todo","type":"unstarted"}}}]},
   "inverseRelations":{"nodes":[{"id":"r2","type":"blocks",
     "issue":{"id":"u9","identifier":"OPS-9","title":"Outside","state":{"name":"Doing","type":"started"}}}]}},
  {"id":"u2","identifier":"ENG-2","title":"Two","priority":2,
   "state":{"name":"Todo","type":"unstarted"},"team":{"key":"ENG"},
   "labels":{"nodes":[]},"attachments":{"nodes":[]},
   "relations":{"nodes":[{"id":"r3","type":"similar",
     "relatedIssue":{"id":"u3","identifier":"ENG-3","title":"Three","state":{"name":"Done","type":"completed"}}}]},
   "inverseRelations":{"nodes":[{"id":"r1","type":"blocks",
     "issue":{"id":"u1","identifier":"ENG-1","title":"One","state":{"name":"Todo","type":"unstarted"}}}]}}
],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-1"}}}}"""

private const val PAGE_TWO = """{"data":{"issues":{"nodes":[
  {"id":"u3","identifier":"ENG-3","title":"Three","priority":0,
   "state":{"name":"Duplicate","type":"duplicate"},"team":{"key":"ENG"},
   "labels":{"nodes":[{"name":"release-2026.1"}]},"attachments":{"nodes":[]},
   "relations":{"nodes":[]},"inverseRelations":{"nodes":[]}}
],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""

private fun client(vararg responses: Canned): Pair<LinearClient, HttpRecorder> {
    val recorder = HttpRecorder(responses.toList())
    return LinearClient(recorder.client, apiKeyAuth("lin_api_key")) to recorder
}

private const val ISSUE_ID = """{"data":{"issues":{"nodes":[{"id":"u1","identifier":"ENG-1"}]}}}"""
private const val UPDATE_OK = """{"data":{"issueUpdate":{"success":true}}}"""

class LinearClientTest {
    @Test
    fun pagesThroughEveryIssueAndSendsTheApiKeyWithoutBearer() = runTest {
        val (linear, recorder) = client(Canned(PAGE_ONE), Canned(PAGE_TWO))

        val graph = linear.getIssuesWithRelations()

        assertEquals(listOf("ENG-1", "ENG-2", "ENG-3"), graph.nodes.map { it.identifier })
        assertEquals(2, recorder.requests.size)
        assertEquals("lin_api_key", recorder.requests[0].headers["Authorization"])
        assertContains(recorder.bodies[0], "\"first\":250")
        assertContains(recorder.bodies[1], "\"after\":\"cursor-1\"")
    }

    @Test
    fun relationsAreDedupedAndUnknownTypesDropped() = runTest {
        val (linear, _) = client(Canned(PAGE_ONE), Canned(PAGE_TWO))

        val edges = linear.getIssuesWithRelations().edges

        // r1 arrives from both sides and counts once; r3 is `similar`, which is not a graph edge.
        assertEquals(2, edges.size)
        assertEquals(setOf("r1", "r2"), edges.mapNotNull { it.relationId }.toSet())
        val blocking = edges.single { it.relationId == "r1" }
        assertEquals("ENG-1", blocking.from)
        assertEquals("ENG-2", blocking.to)
        assertEquals(RelationType.BLOCKS, blocking.type)
    }

    @Test
    fun blockersOutsideTheFetchedSetKeepTheirState() = runTest {
        val (linear, _) = client(Canned(PAGE_ONE), Canned(PAGE_TWO))

        val graph = linear.getIssuesWithRelations()

        assertEquals(mapOf("OPS-9" to WorkflowStateType.STARTED), graph.externalBlockerStates)
    }

    @Test
    fun issueFieldsMapAcrossIncludingPullRequestsAndTheDuplicateState() = runTest {
        val (linear, _) = client(Canned(PAGE_ONE), Canned(PAGE_TWO))

        val nodes = linear.getIssuesWithRelations().nodes

        val first = nodes.first()
        assertEquals("Ada", first.assignee)
        assertEquals("Core", first.project)
        assertEquals("pm-1", first.milestoneId)
        assertEquals("M2 Clients", first.milestone)
        assertEquals(1, first.priority)
        assertEquals(3, first.estimate)
        assertEquals(listOf("bug"), first.labels)
        assertEquals(1, first.pullRequests?.size)
        assertEquals("https://github.com/acme/app/pull/7", first.pullRequests?.first()?.url)
        assertNull(nodes[1].assignee)
        // No projectMilestone key on the wire at all: absent, not just null-named, must decode clean.
        assertNull(nodes[1].milestone)
        assertNull(nodes[1].milestoneId)
        // Linear's native duplicate state is closed, so it reads as canceled.
        assertEquals(WorkflowStateType.CANCELED, nodes[2].stateType)
    }

    @Test
    fun excludeLabelDropsIssuesAfterFetching() = runTest {
        val (linear, recorder) = client(Canned(PAGE_ONE), Canned(PAGE_TWO))

        val graph = linear.getIssuesWithRelations(FilterOptions(excludeLabel = "release"))

        assertEquals(listOf("ENG-1", "ENG-2"), graph.nodes.map { it.identifier })
        assertTrue(recorder.bodies.none { it.contains("release") }, "excludeLabel must not reach Linear")
    }

    @Test
    fun aKnownProjectIdSkipsTheProjectLookup() = runTest {
        val (linear, recorder) = client(Canned(PAGE_TWO))

        linear.getIssueNodes(FilterOptions(project = "Core", projectId = "project-uuid-1"))

        assertEquals(1, recorder.requests.size)
        assertContains(recorder.bodies[0], "\"project\":{\"id\":{\"eq\":\"project-uuid-1\"}}")
    }

    @Test
    fun aRateLimitedCallRetriesOnceAfterTheResetHeader() = runTest {
        val resetAt = Clock.System.now().toEpochMilliseconds() + 1_000
        val (linear, recorder) = client(
            Canned(
                """{"errors":[{"message":"rate limited","extensions":{"code":"RATELIMITED"}}]}""",
                HttpStatusCode.BadRequest,
                headersOf("X-RateLimit-Requests-Reset", resetAt.toString()),
            ),
            Canned(PAGE_TWO),
        )

        val nodes = linear.getIssueNodes()

        assertEquals(listOf("ENG-3"), nodes.map { it.identifier })
        assertEquals(2, recorder.requests.size)
    }

    @Test
    fun aSecondRateLimitFailsTyped() = runTest {
        val (linear, recorder) = client(
            Canned(
                """{"errors":[{"message":"rate limited","extensions":{"code":"RATELIMITED"}}]}""",
                HttpStatusCode.BadRequest,
                headersOf("Retry-After", "2"),
            )
        )

        assertFailsWith<RateLimitedError> { linear.getIssueNodes() }
        assertEquals(2, recorder.requests.size)
    }

    @Test
    fun aWaitLongerThanTheCapFailsWithoutRetrying() = runTest {
        val (linear, recorder) = client(
            Canned(
                """{"errors":[{"message":"rate limited","extensions":{"code":"RATELIMITED"}}]}""",
                HttpStatusCode.BadRequest,
                headersOf("Retry-After", "600"),
            )
        )

        assertFailsWith<RateLimitedError> { linear.getIssueNodes() }
        assertEquals(1, recorder.requests.size)
    }

    @Test
    fun teamsAreFetchedOnceAndThenCached() = runTest {
        val teams = """{"data":{"teams":{"nodes":[{"id":"t1","key":"ENG","name":"Engineering"}]}}}"""
        val (linear, recorder) = client(Canned(teams))

        assertEquals("Engineering", linear.getTeams().single().name)
        assertEquals("t1", linear.getTeamByName("eng")?.id)
        assertEquals(1, recorder.requests.size)
    }

    @Test
    fun statesAreFetchedOnceAndThenCachedPerTeam() = runTest {
        val states = """{"data":{"workflowStates":{"nodes":[
          {"id":"s2","name":"In Progress","type":"started","position":2},
          {"id":"s1","name":"Todo","type":"unstarted","position":1}]}}}"""
        val (linear, recorder) = client(Canned(states))

        val result = linear.getStates("team-1")

        assertEquals(listOf("Todo", "In Progress"), result.map { it.name }, "states come back in workflow order")
        assertEquals(WorkflowStateType.UNSTARTED, result.first().type)
        assertContains(recorder.bodies[0], "\"teamId\":\"team-1\"")

        linear.getStates("team-1")
        assertEquals(1, recorder.requests.size)
    }

    @Test
    fun explicitNullFieldsClearWhileAbsentFieldsAreOmitted() = runTest {
        val (linear, recorder) = client(
            Canned(ISSUE_ID), Canned(UPDATE_OK),
            Canned(ISSUE_ID), Canned(UPDATE_OK),
            Canned(ISSUE_ID), Canned(UPDATE_OK),
            Canned(ISSUE_ID), Canned(UPDATE_OK),
        )

        linear.updateIssue("ENG-1", IssueUpdate(priority = 1))
        assertTrue("estimate" !in recorder.bodies[1] && "projectId" !in recorder.bodies[1])

        linear.updateIssue("ENG-1", IssueUpdate(estimate = 5))
        assertContains(recorder.bodies[3], "\"estimate\":5")

        linear.updateIssue("ENG-1", IssueUpdate(clearEstimate = true))
        assertContains(recorder.bodies[5], "\"estimate\":null")

        linear.updateIssue("ENG-1", IssueUpdate(clearProject = true))
        assertContains(recorder.bodies[7], "\"projectId\":null")
    }

    @Test
    fun attachPrUrlResolvesTheIdentifierAndSendsTheUrl() = runTest {
        val (linear, recorder) = client(
            Canned(ISSUE_ID),
            Canned("""{"data":{"attachmentLinkURL":{"success":true}}}"""),
        )

        linear.attachPrUrl("ENG-1", "https://github.com/acme/app/pull/9")

        assertContains(recorder.bodies[1], "\"issueId\":\"u1\"")
        assertContains(recorder.bodies[1], "\"url\":\"https://github.com/acme/app/pull/9\"")
    }

    @Test
    fun aRefusedAttachThrows() = runTest {
        val (linear, _) = client(
            Canned(ISSUE_ID),
            Canned("""{"data":{"attachmentLinkURL":{"success":false}}}"""),
        )

        assertFailsWith<ApiError> { linear.attachPrUrl("ENG-1", "https://github.com/acme/app/pull/9") }
    }

    @Test
    fun projectSummariesCarryTheirLinearUrl() = runTest {
        val page = """{"data":{"projects":{"nodes":[
          {"id":"p1","name":"Core","state":"started","url":"https://linear.app/acme/project/core-abc",
           "teams":{"nodes":[{"key":"ENG"}]}}],
          "pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""
        val (linear, _) = client(Canned(page))

        assertEquals("https://linear.app/acme/project/core-abc", linear.getProjectSummaries().single().url)
    }

    @Test
    fun theWorkspaceUrlKeyIsFetchedOnceAndThenCached() = runTest {
        val (linear, recorder) = client(Canned("""{"data":{"organization":{"urlKey":"acme"}}}"""))

        assertEquals("acme", linear.getWorkspaceUrlKey())
        assertEquals("acme", linear.getWorkspaceUrlKey())
        assertEquals(1, recorder.requests.size)
    }

    @Test
    fun teamUrlBuildsTheLinearTeamPage() {
        assertEquals("https://linear.app/acme/team/ENG", teamUrl("acme", "ENG"))
    }
}
