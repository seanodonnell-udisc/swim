@file:OptIn(ExperimentalCoroutinesApi::class)

package swim.core.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import swim.core.Canned
import swim.core.HttpRecorder
import swim.core.github.GithubClient
import swim.core.linear.LinearClient
import swim.core.linear.apiKeyAuth
import swim.core.model.ApiError
import swim.core.model.FilterOptions
import swim.core.model.IssueEdge
import swim.core.model.PrStatus
import swim.core.model.RelationType
import swim.layout.Position
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraphSessionTest {
    @Test
    fun noCollectorMeansNoRequest() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))

        f.store.applyFilters()
        f.settle()

        assertEquals(0, f.graphRequests)
        assertEquals(GraphState.NotLoaded, f.session.graph.value)
    }

    @Test
    fun theGraphLoadsWhenTheFilterStoreSaysItShould() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        val states = f.collectGraph()

        f.settle()
        assertEquals(listOf(GraphState.NotLoaded), states)

        // A label needs no lookup, so an armed load stays the only request this test can make.
        f.store.setLabel("bug")
        f.settle()
        assertEquals(listOf(GraphState.NotLoaded), states)

        f.store.applyFilters()
        f.await { states.size == 3 }

        assertEquals(GraphState.Loading, states[1])
        val loaded = assertIs<GraphState.Loaded>(states[2])
        assertEquals(listOf("ENG-1", "ENG-2", "ENG-3"), loaded.data.nodes.map { it.identifier })
        assertEquals(1, f.graphRequests)
    }

    @Test
    fun applyingTheSameFiltersTwiceLoadsOneTime() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        f.collectGraph()

        f.store.applyFilters()
        f.await { f.graphRequests == 1 }
        f.store.applyFilters()
        f.settle()

        assertEquals(1, f.graphRequests)
    }

    @Test
    fun reloadAsksAgainWithTheSameFilters() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        f.collectGraph()

        f.store.applyFilters()
        f.await { f.graphRequests == 1 }
        f.session.reload()
        f.await { f.graphRequests == 2 }
    }

    @Test
    fun theAssigneeIdComesBackWithTheIssue() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        val states = f.collectGraph()

        f.store.applyFilters()
        f.await { states.last() is GraphState.Loaded }

        val loaded = assertIs<GraphState.Loaded>(states.last())
        assertEquals("user-1", loaded.data.nodes.first().assigneeId)
    }

    @Test
    fun aRefusedRequestBecomesTheErrorState() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned("""{"errors":[{"message":"Bad filter"}]}""", HttpStatusCode.BadRequest))
        val states = f.collectGraph()

        f.store.applyFilters()
        f.await { states.last() is GraphState.Error }

        val failed = assertIs<GraphState.Error>(states.last())
        assertIs<ApiError>(failed.error)
    }

    @Test
    fun aMutationReloadsTheGraph() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(
            Canned(GRAPH_PAGE),
            Canned(ISSUE_ID),
            Canned(UPDATE_OK),
            Canned(GRAPH_PAGE),
        )
        f.collectGraph()
        f.store.applyFilters()
        f.await { f.graphRequests == 1 }

        f.session.setAssignee("ENG-1", "user-2")
        f.await { f.graphRequests == 2 }

        assertContains(f.recorder.bodies[2], "issueUpdate")
    }

    @Test
    fun aRefusedMutationThrowsAndDoesNotReload() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(
            Canned(GRAPH_PAGE),
            Canned(ISSUE_ID),
            Canned(ISSUE_ID),
            Canned("""{"data":{"issueRelationCreate":{"success":false}}}"""),
        )
        f.collectGraph()
        f.store.applyFilters()
        f.await { f.graphRequests == 1 }

        assertFailsWith<ApiError> {
            f.session.createRelation("ENG-1", "ENG-2", RelationType.RELATED)
        }
        f.settle()

        assertEquals(1, f.graphRequests)
    }

    @Test
    fun blockedByCreatesTheBlocksRelationWithTheEndsSwapped() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(
            Canned("""{"data":{"issues":{"nodes":[{"id":"u2","identifier":"ENG-2"}]}}}"""),
            Canned("""{"data":{"issues":{"nodes":[{"id":"u1","identifier":"ENG-1"}]}}}"""),
            Canned("""{"data":{"issueRelationCreate":{"success":true,"issueRelation":{"id":"r9"}}}}"""),
        )

        assertEquals("r9", f.session.createBlockedBy("ENG-1", "ENG-2"))

        // "ENG-1 is blocked by ENG-2" is the relation "ENG-2 blocks ENG-1".
        val body = f.recorder.bodies.last()
        assertContains(body, "\"issueId\":\"u2\"")
        assertContains(body, "\"relatedIssueId\":\"u1\"")
    }

    @Test
    fun changingARelationDeletesThenCreates() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(
            Canned("""{"data":{"issueRelationDelete":{"success":true}}}"""),
            Canned(ISSUE_ID),
            Canned(ISSUE_ID),
            Canned("""{"data":{"issueRelationCreate":{"success":true,"issueRelation":{"id":"r9"}}}}"""),
        )
        val edge = IssueEdge("ENG-1", "ENG-2", RelationType.BLOCKS, relationId = "r1")

        f.session.changeRelation(edge, RelationType.RELATED)

        assertContains(f.recorder.bodies[0], "issueRelationDelete")
        assertContains(f.recorder.bodies[3], "\"type\":\"related\"")
    }

    @Test
    fun anEdgeWithNoRelationIdCannotBeChangedOrRemoved() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        val edge = IssueEdge("ENG-1", "ENG-2", RelationType.BLOCKS)

        assertFailsWith<IllegalArgumentException> { f.session.removeRelation(edge) }
        assertFailsWith<IllegalArgumentException> { f.session.changeRelation(edge, RelationType.RELATED) }
    }

    @Test
    fun theReadySetHoldsTheUnblockedIssues() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        backgroundScope.launch { f.session.readySet.collect {} }

        f.store.applyFilters()
        f.await { f.session.readySet.value.isNotEmpty() }

        assertEquals(setOf("ENG-1", "ENG-3"), f.session.readySet.value)
    }

    @Test
    fun theProjectionHidesDuplicatesByDefault() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        backgroundScope.launch { f.session.projected.collect {} }

        f.store.applyFilters()
        f.await { f.session.projected.value.nodes.isNotEmpty() }

        val projected = f.session.projected.value
        assertEquals(listOf("ENG-1", "ENG-2"), projected.nodes.map { it.identifier })
        assertEquals(listOf("r1"), projected.edges.map { it.relationId })
    }

    @Test
    fun showingDuplicatesAndHidingRelatedEdgesAreSeparateToggles() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        backgroundScope.launch { f.session.projected.collect {} }
        f.store.applyFilters()
        f.await { f.session.projected.value.nodes.isNotEmpty() }

        f.session.setShowDuplicates(true)
        f.await { f.session.projected.value.nodes.size == 3 }
        assertEquals(3, f.session.projected.value.edges.size)

        f.session.setShowRelatedEdges(false)
        f.await { f.session.projected.value.edges.size == 2 }
        assertEquals(
            listOf(RelationType.BLOCKS, RelationType.DUPLICATE),
            f.session.projected.value.edges.map { it.type },
        )
    }

    @Test
    fun pullRequestStatusesArriveWithTheGraphAndAreNotAskedForTwice() = runTest(UnconfinedTestDispatcher()) {
        val github = HttpRecorder(Canned(PR_STATUS))
        val f = fixture(
            Canned(GRAPH_PAGE),
            github = GithubClient(github.client) { "gho_token" },
        )
        backgroundScope.launch { f.session.prStatuses.collect {} }

        f.store.applyFilters()
        f.await { f.session.prStatuses.value.isNotEmpty() }

        assertEquals(
            mapOf("https://github.com/acme/app/pull/7" to PrStatus("APPROVED", "SUCCESS")),
            f.session.prStatuses.value,
        )

        f.session.reload()
        f.await { f.graphRequests == 2 }
        f.settle()
        assertEquals(1, github.requests.size)
    }

    @Test
    fun withoutAGithubClientThereAreNoPullRequestStatuses() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        backgroundScope.launch { f.session.prStatuses.collect {} }

        f.store.applyFilters()
        f.await { f.graphRequests == 1 }
        f.settle()

        assertTrue(f.session.prStatuses.value.isEmpty())
    }

    @Test
    fun draggedPositionsMergeIntoTheCurrentQuerysSavedLayout() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        f.store.setTeam("ENG")
        val key = f.session.layoutCacheKey()

        f.session.savePositions(mapOf("ENG-1" to Position(1f, 2f)))
        f.session.savePositions(mapOf("ENG-2" to Position(3f, 4f), "ENG-1" to Position(5f, 6f)))

        assertEquals(
            mapOf("ENG-1" to Position(5f, 6f), "ENG-2" to Position(3f, 4f)),
            f.positions.get().byKey.getValue(key),
        )
        assertEquals(cacheKey(FilterOptions(team = "ENG"), GraphGrouping.NONE), key)
    }

    @Test
    fun theLayoutKeyFollowsTheLoadedGraphNotTheArmedFilters() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(Canned(GRAPH_PAGE))
        f.collectGraph()
        // A label needs no lookup, so the armed load stays the only request this test can make.
        f.store.setLabel("bug")
        f.store.applyFilters()
        f.await { f.session.graph.value is GraphState.Loaded }
        val loaded = f.session.layoutCacheKey()

        // Selecting a priority only ARMS a load. The `bug` graph is still the one on screen, so
        // its drags must keep going to the `bug` key.
        f.store.setPriority(1)
        f.settle()

        assertEquals(loaded, f.session.layoutCacheKey())
        f.session.savePositions(mapOf("ENG-1" to Position(1f, 2f)))
        assertEquals(setOf(loaded), f.positions.get().byKey.keys)
        assertEquals(cacheKey(FilterOptions(label = "bug"), GraphGrouping.NONE), loaded)
    }

    @Test
    fun aCancelledPullRequestFetchDoesNotSuppressTheNextOne() = runTest(UnconfinedTestDispatcher()) {
        var asked = 0
        val gate = CompletableDeferred<Unit>()
        val slowGithub = HttpClient(
            MockEngine {
                asked++
                if (asked == 1) gate.await()
                respond(PR_STATUS)
            }
        )
        val f = fixture(Canned(GRAPH_PAGE), github = GithubClient(slowGithub) { "gho_token" })
        backgroundScope.launch { f.session.prStatuses.collect {} }

        f.store.applyFilters()
        f.await { asked == 1 }

        // The reload cancels the fetch in flight. Stamping the TTL before the call made the
        // second ask look like a duplicate, so no badge appeared for a minute.
        f.session.reload()
        f.await { f.session.prStatuses.value.isNotEmpty() }

        assertEquals(2, asked)
        gate.complete(Unit)
    }

    @Test
    fun anUnexpectedFailureBecomesAnErrorStateAndTheGraphStillLoadsAfterIt() =
        runTest(UnconfinedTestDispatcher()) {
            var explode = true
            val recorder = HttpRecorder(Canned(GRAPH_PAGE))
            val settings = FakeSettings()
            val store = FilterStore(settings)
            val positions = SettingsPositionStore(settings)
            val session = GraphSession(
                client = LinearClient(
                    recorder.client,
                    { if (explode) throw IllegalStateException("socket reset") else "lin_api_key" },
                ),
                github = null,
                filterStore = store,
                positions = positions,
                scope = backgroundScope,
            )
            val f = Fixture(recorder, store, positions, session, this)
            val states = f.collectGraph()

            store.applyFilters()
            f.await { states.last() is GraphState.Error }
            assertIs<ApiError>(assertIs<GraphState.Error>(states.last()).error)

            // The flow must still be alive. Before the guard, stateIn's coroutine had died.
            explode = false
            session.reload()
            f.await { states.last() is GraphState.Loaded }
        }

    @Test
    fun eachChangeOptionDropsOnlyTheEdgesOwnIdentity() {
        val blocks = changeOptions(IssueEdge("A", "B", RelationType.BLOCKS, "r1"))
        assertEquals(
            listOf(
                RelationChange("blocked by", "B", "A", RelationType.BLOCKS),
                RelationChange("related", "A", "B", RelationType.RELATED),
                RelationChange("duplicate", "A", "B", RelationType.DUPLICATE),
            ),
            blocks,
        )

        val related = changeOptions(IssueEdge("A", "B", RelationType.RELATED, "r2"))
        assertEquals(listOf("blocks", "blocked by", "duplicate"), related.map { it.label })

        val duplicate = changeOptions(IssueEdge("A", "B", RelationType.DUPLICATE, "r3"))
        assertEquals(listOf("blocks", "blocked by", "related"), duplicate.map { it.label })
    }
}

private class Fixture(
    val recorder: HttpRecorder,
    val store: FilterStore,
    val positions: PositionStore,
    val session: GraphSession,
    private val scope: TestScope,
) {
    val graphRequests: Int get() = recorder.bodies.count { it.contains("IssuesWithRelations") }

    fun collectGraph(): List<GraphState> {
        val states = mutableListOf<GraphState>()
        scope.backgroundScope.launch { session.graph.toList(states) }
        return states
    }

    // The mock HTTP engine answers on its own threads, so the waits here are real, not virtual.
    suspend fun await(ready: () -> Boolean): Unit = withContext(Dispatchers.Default) {
        withTimeout(AWAIT_MS) { while (!ready()) delay(POLL_MS) }
    }

    suspend fun settle(): Unit = withContext(Dispatchers.Default) { delay(SETTLE_MS) }
}

private fun TestScope.fixture(vararg responses: Canned, github: GithubClient? = null): Fixture {
    val recorder = HttpRecorder(responses.toList())
    val settings = FakeSettings()
    val store = FilterStore(settings)
    val positions = SettingsPositionStore(settings)
    val session = GraphSession(
        client = LinearClient(recorder.client, apiKeyAuth("lin_api_key")),
        github = github,
        filterStore = store,
        positions = positions,
        scope = backgroundScope,
    )
    return Fixture(recorder, store, positions, session, this)
}

private const val GRAPH_PAGE = """{"data":{"issues":{"nodes":[
  {"id":"u1","identifier":"ENG-1","title":"One","priority":1,
   "state":{"name":"Todo","type":"unstarted"},"team":{"key":"ENG"},
   "assignee":{"id":"user-1","name":"Ada"},
   "labels":{"nodes":[]},
   "attachments":{"nodes":[{"url":"https://github.com/acme/app/pull/7","title":"Fix it"}]},
   "relations":{"nodes":[{"id":"r1","type":"blocks",
     "relatedIssue":{"id":"u2","identifier":"ENG-2","title":"Two","state":{"name":"Todo","type":"unstarted"}}}]},
   "inverseRelations":{"nodes":[]}},
  {"id":"u2","identifier":"ENG-2","title":"Two","priority":2,
   "state":{"name":"Todo","type":"unstarted"},"team":{"key":"ENG"},
   "labels":{"nodes":[]},"attachments":{"nodes":[]},
   "relations":{"nodes":[]},
   "inverseRelations":{"nodes":[{"id":"r1","type":"blocks",
     "issue":{"id":"u1","identifier":"ENG-1","title":"One","state":{"name":"Todo","type":"unstarted"}}}]}},
  {"id":"u3","identifier":"ENG-3","title":"Three","priority":0,
   "state":{"name":"Todo","type":"unstarted"},"team":{"key":"ENG"},
   "labels":{"nodes":[]},"attachments":{"nodes":[]},
   "relations":{"nodes":[
     {"id":"r2","type":"duplicate",
      "relatedIssue":{"id":"u2","identifier":"ENG-2","title":"Two","state":{"name":"Todo","type":"unstarted"}}},
     {"id":"r3","type":"related",
      "relatedIssue":{"id":"u1","identifier":"ENG-1","title":"One","state":{"name":"Todo","type":"unstarted"}}}]},
   "inverseRelations":{"nodes":[]}}
],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""

private const val ISSUE_ID = """{"data":{"issues":{"nodes":[{"id":"u1","identifier":"ENG-1"}]}}}"""

private const val UPDATE_OK = """{"data":{"issueUpdate":{"success":true}}}"""

private const val AWAIT_MS = 5_000L
private const val POLL_MS = 2L
private const val SETTLE_MS = 60L

private const val PR_STATUS = """{"data":{"pr0":{"pullRequest":{"reviewDecision":"APPROVED",
  "commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS"}}}]}}}}}"""
