package swim.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ModelSerializationTest {
    @Test
    fun relationTypeRoundTripsThroughLowercaseWireValues() {
        for (value in RelationType.entries) {
            val encoded = Json.encodeToString(value)
            assertEquals(encoded, encoded.lowercase())
            assertEquals(value, Json.decodeFromString<RelationType>(encoded))
        }
    }

    @Test
    fun issueNodeRoundTrips() {
        val node = IssueNode(
            id = "uuid-1",
            identifier = "MOB-1",
            title = "Fix the thing",
            state = "In Progress",
            stateType = WorkflowStateType.STARTED,
            priority = 2,
            team = "MOB",
            project = "Launch",
            labels = listOf("bug", "urgent"),
            assignee = "Ada",
            pullRequests = listOf(PullRequestRef(url = "https://example.com/pr/1", title = "Fix")),
        )
        assertEquals(node, Json.decodeFromString<IssueNode>(Json.encodeToString(node)))
    }

    @Test
    fun issueNodeRoundTripsWithTimestamps() {
        val node = IssueNode(
            id = "uuid-1", identifier = "MOB-1", title = "T", state = "Todo",
            stateType = WorkflowStateType.UNSTARTED, priority = 0, team = "MOB",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-02-01T12:30:00Z"),
        )
        assertEquals(node, Json.decodeFromString<IssueNode>(Json.encodeToString(node)))
    }

    @Test
    fun issueEdgeRoundTrips() {
        val edge = IssueEdge(from = "MOB-1", to = "MOB-2", type = RelationType.BLOCKS, relationId = "rel-1")
        assertEquals(edge, Json.decodeFromString<IssueEdge>(Json.encodeToString(edge)))
    }

    @Test
    fun graphDataRoundTrips() {
        val graph = GraphData(
            nodes = listOf(
                IssueNode(
                    id = "u1", identifier = "MOB-1", title = "T", state = "Todo",
                    stateType = WorkflowStateType.UNSTARTED, priority = 0, team = "MOB",
                ),
            ),
            edges = listOf(IssueEdge("MOB-1", "MOB-2", RelationType.BLOCKS)),
            externalBlockerStates = mapOf("MOB-2" to WorkflowStateType.COMPLETED),
        )
        assertEquals(graph, Json.decodeFromString<GraphData>(Json.encodeToString(graph)))
    }

    @Test
    fun filterOptionsRoundTripsWithProjectId() {
        val filters = FilterOptions(team = "MOB", project = "Launch", projectId = "abc123")
        assertEquals(filters, Json.decodeFromString<FilterOptions>(Json.encodeToString(filters)))
    }

    @Test
    fun priorityLabelsCoverAllFiveLevels() {
        assertEquals(setOf(0, 1, 2, 3, 4), PRIORITY_LABELS.keys)
        assertEquals("No priority", PRIORITY_LABELS[0])
        assertEquals("Urgent", PRIORITY_LABELS[1])
    }

    @Test
    fun scopeAndNotFoundErrorsCarryTheirMessage() {
        assertTrue(ScopeError("no team").message == "no team")
        assertTrue(NotFoundError("MOB-1 not found").message == "MOB-1 not found")
    }
}
