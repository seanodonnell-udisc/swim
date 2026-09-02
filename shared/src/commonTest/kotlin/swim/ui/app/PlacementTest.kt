package swim.ui.app

import swim.core.model.GraphData
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.RelationType
import swim.core.model.WorkflowStateType
import swim.core.session.GraphGrouping
import swim.layout.Position
import swim.layout.PositionSnapshot
import swim.ui.graph.GraphCanvasDefaults
import swim.ui.graph.blocksEdgeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

private fun node(id: String, team: String = "ENG", project: String? = null, labels: List<String> = emptyList()) =
    IssueNode(
        id = id,
        identifier = id,
        title = id,
        state = "Todo",
        stateType = WorkflowStateType.UNSTARTED,
        priority = 0,
        team = team,
        project = project,
        labels = labels,
    )

private const val KEY = "team=ENG"

private fun blocks(from: String, to: String) = IssueEdge(from, to, RelationType.BLOCKS, "rel-$from-$to")

class PlacementTest {

    @Test
    fun everyNodeGetsTheFixedCardBox() {
        val graph = GraphData(listOf(node("A"), node("B")), emptyList())
        val nodes = layoutNodesOf(graph)
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.width == GraphCanvasDefaults.NodeWidth })
        assertTrue(nodes.all { it.height == GraphCanvasDefaults.NodeHeight })
    }

    @Test
    fun duplicateEdgesDoNotShapeThePlacement() {
        val graph = GraphData(
            nodes = listOf(node("A"), node("B"), node("C")),
            edges = listOf(
                blocks("A", "B"),
                IssueEdge("C", "B", RelationType.DUPLICATE, "r1"),
                IssueEdge("A", "C", RelationType.RELATED, "r2"),
            ),
        )
        val edges = layoutEdgesOf(graph)
        assertEquals(2, edges.size)
        assertTrue(edges.none { it.from == "C" && it.to == "B" })
    }

    @Test
    fun blockedNodeSitsBelowItsBlocker() {
        val graph = GraphData(listOf(node("A"), node("B")), listOf(blocks("A", "B")))
        val placed = placeGraph(graph, GraphGrouping.NONE, "k", PositionSnapshot())
        assertTrue(placed.positions.getValue("B").y > placed.positions.getValue("A").y)
        assertTrue(placed.groups.isEmpty())
    }

    @Test
    fun aCycleIsReportedAsAnEdgeKeyTheCanvasCanRestyle() {
        val graph = GraphData(
            nodes = listOf(node("A"), node("B"), node("C")),
            edges = listOf(blocks("A", "B"), blocks("B", "C"), blocks("C", "A")),
        )
        val placed = placeGraph(graph, GraphGrouping.NONE, "k", PositionSnapshot())
        assertEquals(1, placed.cycleEdges.size)
        val cycle = placed.cycleEdges.single()
        assertContains(
            listOf(blocksEdgeKey("A", "B"), blocksEdgeKey("B", "C"), blocksEdgeKey("C", "A")),
            cycle,
        )
    }

    @Test
    fun groupsAreLaidOutSideBySideAndBoxed() {
        val graph = GraphData(
            nodes = listOf(node("A", team = "ENG"), node("B", team = "ENG"), node("C", team = "OPS")),
            edges = listOf(blocks("A", "B")),
        )
        val placed = placeGraph(graph, GraphGrouping.TEAM, "k", PositionSnapshot())
        assertEquals(listOf("ENG", "OPS"), placed.groups.map { it.label })

        val eng = placed.groups.first { it.label == "ENG" }
        val ops = placed.groups.first { it.label == "OPS" }
        assertTrue(ops.x >= eng.x + eng.width, "groups overlap: $eng and $ops")

        // The outline leaves room for the label above the topmost card.
        assertTrue(placed.positions.getValue("A").y - eng.y >= GROUP_LABEL_BAND)
    }

    @Test
    fun nodesWithoutAProjectStillGetAGroup() {
        val graph = GraphData(listOf(node("A", project = "Swim"), node("B")), emptyList())
        val placed = placeGraph(graph, GraphGrouping.PROJECT, "k", PositionSnapshot())
        assertEquals(setOf("Swim", "No project"), placed.groups.map { it.label }.toSet())
    }

    @Test
    fun savedPositionsWin() {
        val graph = GraphData(listOf(node("A"), node("B")), listOf(blocks("A", "B")))
        val saved = PositionSnapshot(mapOf("k" to mapOf("A" to Position(900f, 40f))))
        val placed = placeGraph(graph, GraphGrouping.NONE, "k", saved)
        assertEquals(Position(900f, 40f), placed.positions.getValue("A"))
    }

    @Test
    fun aDropIsFinalWhenTheWholeLayoutIsPersisted() {
        val graph = GraphData(
            nodes = listOf(node("A"), node("B"), node("C"), node("D")),
            edges = listOf(blocks("A", "B"), blocks("A", "C"), blocks("B", "D")),
        )
        val first = placeGraph(graph, GraphGrouping.NONE, KEY, PositionSnapshot())

        // The user drops A exactly on top of B. Overlapping cards are their prerogative.
        val dropped = first.positions + ("A" to first.positions.getValue("B"))
        val snapshot = PositionSnapshot(mapOf(KEY to dropped))

        // Any reload re-runs the placement pass. Nothing may move.
        val again = placeGraph(graph, GraphGrouping.NONE, KEY, snapshot)
        assertEquals(dropped, again.positions)
        assertEquals(
            again.positions.getValue("B"),
            again.positions.getValue("A"),
            "the overlap was resolved away behind the user's back",
        )

        // And it survives one more round trip through the snapshot it reports.
        val third = placeGraph(graph, GraphGrouping.NONE, KEY, again.snapshot)
        assertEquals(dropped, third.positions)
    }

    @Test
    fun persistingOnlyTheMovedNodeLetsTheRestBeReArranged() {
        // This is what a drop must NOT save. It is the shape of the defect, kept as a guard.
        val graph = GraphData(
            nodes = listOf(node("A"), node("B"), node("C"), node("D")),
            edges = listOf(blocks("A", "B"), blocks("A", "C"), blocks("B", "D")),
        )
        val first = placeGraph(graph, GraphGrouping.NONE, KEY, PositionSnapshot())
        val onlyMoved = PositionSnapshot(
            mapOf(KEY to mapOf("A" to first.positions.getValue("B"))),
        )
        val again = placeGraph(graph, GraphGrouping.NONE, KEY, onlyMoved)
        assertTrue(
            again.positions.filterKeys { it != "A" } != first.positions.filterKeys { it != "A" },
            "a one-node snapshot no longer disturbs the others, so the drop may save just one",
        )
    }

    @Test
    fun aGroupBoxTracksTheCardsInsideIt() {
        val graph = GraphData(listOf(node("A", team = "ENG")), emptyList())
        val boxes = groupBoxesOf(graph, GraphGrouping.TEAM, mapOf("A" to Position(100f, 200f)))
        val box = boxes.single()
        assertEquals(100f - GROUP_MARGIN, box.x)
        assertEquals(200f - GROUP_LABEL_BAND, box.y)
        assertEquals(GraphCanvasDefaults.NodeWidth + GROUP_MARGIN * 2f, box.width)
    }

    @Test
    fun theAutoloadFlagReadsTeamAndProject() {
        assertEquals("SEA", parseAutoload("SEA/Swim Sandbox")?.team)
        assertEquals("Swim Sandbox", parseAutoload("SEA/Swim Sandbox")?.project)
        assertEquals("SEA", parseAutoload(" SEA ")?.team)
        assertEquals(null, parseAutoload("SEA")?.project)
        assertEquals(null, parseAutoload("  "))
    }
}
