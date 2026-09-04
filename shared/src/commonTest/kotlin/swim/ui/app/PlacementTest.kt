package swim.ui.app

import swim.core.model.GraphData
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.RelationType
import swim.core.model.WorkflowStateType
import swim.core.model.FilterOptions
import swim.core.session.EDITED_KEY
import swim.core.session.GraphGrouping
import swim.core.session.HAND_EDITED
import swim.core.session.SettingsPositionStore
import swim.core.session.cacheKey
import androidx.compose.ui.geometry.Offset
import swim.layout.LayoutEdge
import swim.layout.LayoutEdgeKind
import swim.layout.Position
import swim.layout.PositionSnapshot
import swim.layout.relayoutDescendants
import swim.ui.graph.GraphCanvasDefaults
import swim.ui.graph.STACK_OFFSET
import swim.ui.graph.STACK_PREFIX
import swim.ui.graph.blocksEdgeKey
import swim.ui.graph.layoutEdgesOf
import swim.ui.graph.layoutNodesOf
import swim.ui.graph.stackKeyOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

private fun node(
    id: String,
    team: String = "ENG",
    project: String? = null,
    labels: List<String> = emptyList(),
    milestone: String? = null,
    milestoneSortOrder: Float? = null,
) =
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
        milestone = milestone,
        milestoneSortOrder = milestoneSortOrder,
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

    private val milestoned = GraphData(
        nodes = listOf(
            node("A", milestone = "M1"),
            node("B", milestone = "M1"),
            node("C", milestone = "M2"),
            node("D"),
        ),
        // A blocks B inside M1; A blocks C across areas; D has no milestone.
        edges = listOf(blocks("A", "B"), blocks("A", "C"), blocks("C", "D")),
    )

    @Test
    fun theNoMilestoneAreaComesLast() {
        val placed = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, PositionSnapshot())
        assertEquals(listOf("M1", "M2", "No milestone"), placed.groups.map { it.label })
    }

    @Test
    fun areasFollowSortOrderNotName() {
        val graph = GraphData(
            nodes = listOf(
                node("A", milestone = "Launch", milestoneSortOrder = 2f),
                node("B", milestone = "Foundations", milestoneSortOrder = 0f),
                node("C", milestone = "Service", milestoneSortOrder = 1f),
            ),
            edges = emptyList(),
        )
        val placed = placeGraph(graph, GraphGrouping.MILESTONE, KEY, PositionSnapshot())
        assertEquals(listOf("Foundations", "Service", "Launch"), placed.groups.map { it.label })
    }

    @Test
    fun aSortOrderTieFallsBackToName() {
        val graph = GraphData(
            nodes = listOf(
                node("A", milestone = "Zebra", milestoneSortOrder = 0f),
                node("B", milestone = "Alpha", milestoneSortOrder = 0f),
            ),
            edges = emptyList(),
        )
        val placed = placeGraph(graph, GraphGrouping.MILESTONE, KEY, PositionSnapshot())
        assertEquals(listOf("Alpha", "Zebra"), placed.groups.map { it.label })
    }

    @Test
    fun onlyTheEdgesInsideOneAreaSurviveTheCrossFilter() {
        val hidden = withoutCrossGroupEdges(milestoned, GraphGrouping.MILESTONE)
        assertEquals(listOf("A" to "B"), hidden.edges.map { it.from to it.to })
        // Nothing is hidden when the graph is not grouped.
        assertEquals(milestoned.edges, withoutCrossGroupEdges(milestoned, GraphGrouping.NONE).edges)
    }

    @Test
    fun anAreaDragMovesEveryMemberAndNothingElse() {
        val placed = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, PositionSnapshot())
        val ids = idsIn(milestoned, GraphGrouping.MILESTONE, "M1")
        assertEquals(setOf("A", "B"), ids)

        val moved = moveGroup(placed.positions, ids, Offset(120f, -40f))
        ids.forEach { id ->
            val was = placed.positions.getValue(id)
            assertEquals(Position(was.x + 120f, was.y - 40f), moved.getValue(id))
        }
        assertEquals(placed.positions.getValue("C"), moved.getValue("C"))
        assertEquals(placed.positions.getValue("D"), moved.getValue("D"))
    }

    @Test
    fun theStoredAreaOffsetOnlyMovesMembersThatAreNotHandPlaced() {
        val first = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, PositionSnapshot())
        // The user dragged M1 right by 300. A and B were persisted at their new spots, and the
        // area kept the offset so a member placed later lands inside the area that moved.
        val ids = idsIn(milestoned, GraphGrouping.MILESTONE, "M1")
        val dropped = moveGroup(first.positions, ids, Offset(300f, 0f))
        val snapshot = PositionSnapshot(
            mapOf(
                KEY to dropped.filterKeys { it in ids } +
                    (groupOffsetKey("M1") to Position(300f, 0f)),
            ),
        )
        assertEquals(mapOf("M1" to Position(300f, 0f)), groupOffsetsIn(snapshot, KEY))

        val again = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, snapshot)
        // A and B are hand-placed, so they are exactly where they were dropped.
        ids.forEach { assertEquals(dropped.getValue(it), again.positions.getValue(it)) }
        // The area outline followed them.
        val m1 = again.groups.first { it.label == "M1" }
        assertTrue(m1.x > first.groups.first { it.label == "M1" }.x)
    }

    @Test
    fun aFreshMemberLandsInsideTheAreaThatWasDragged() {
        val before = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, PositionSnapshot())
        // Less than the gap between two areas, so M1 does not reach M2 and nothing is separated.
        val onlyOffset = PositionSnapshot(mapOf(KEY to mapOf(groupOffsetKey("M1") to Position(100f, 55f))))
        val after = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, onlyOffset)
        listOf("A", "B").forEach { id ->
            val was = before.positions.getValue(id)
            assertEquals(Position(was.x + 100f, was.y + 55f), after.positions.getValue(id))
        }
        assertEquals(before.positions.getValue("C"), after.positions.getValue("C"))
    }

    @Test
    fun eachGroupingKeepsItsOwnArrangement() {
        val flat = cacheKey(FilterOptions(team = "ENG"), GraphGrouping.NONE)
        val grouped = cacheKey(FilterOptions(team = "ENG"), GraphGrouping.MILESTONE)
        val arrangedFlat = mapOf("A" to Position(10f, 20f))
        val arrangedGrouped = mapOf("A" to Position(999f, 111f))

        // Neither grouping may donate to the other, in either direction.
        val fromFlat = placeGraph(
            milestoned, GraphGrouping.MILESTONE, grouped, PositionSnapshot(mapOf(flat to arrangedFlat)),
        )
        assertTrue(
            fromFlat.positions.getValue("A") != Position(10f, 20f),
            "the milestone view inherited the ungrouped arrangement",
        )
        assertEquals(listOf("M1", "M2", "No milestone"), fromFlat.groups.map { it.label })

        val fromGrouped = placeGraph(
            milestoned, GraphGrouping.NONE, flat, PositionSnapshot(mapOf(grouped to arrangedGrouped)),
        )
        assertTrue(
            fromGrouped.positions.getValue("A") != Position(999f, 111f),
            "the flat view inherited the grouped arrangement",
        )

        // With an arrangement of its own, each key keeps exactly that one.
        val both = PositionSnapshot(mapOf(flat to arrangedFlat, grouped to arrangedGrouped))
        assertEquals(
            Position(999f, 111f),
            placeGraph(milestoned, GraphGrouping.MILESTONE, grouped, both).positions.getValue("A"),
        )
        assertEquals(
            Position(10f, 20f),
            placeGraph(milestoned, GraphGrouping.NONE, flat, both).positions.getValue("A"),
        )
    }

    @Test
    fun aSiblingOfTheSameGroupingStillDonates() {
        // Two variants of one flat query. Reusing the closest layout is the intended feature.
        val mine = cacheKey(FilterOptions(team = "MOB"), GraphGrouping.NONE)
        val sibling = cacheKey(FilterOptions(team = "WEB"), GraphGrouping.NONE)
        val donated = mapOf("A" to Position(700f, 300f), "B" to Position(700f, 500f))
        val placed = placeGraph(
            milestoned, GraphGrouping.NONE, mine, PositionSnapshot(mapOf(sibling to donated)),
        )
        assertEquals(Position(700f, 300f), placed.positions.getValue("A"))
    }

    @Test
    fun reLayoutEscapesItsOwnCacheAndEverySibling() {
        val mine = cacheKey(FilterOptions(team = "MOB"), GraphGrouping.NONE)
        val sibling = cacheKey(FilterOptions(team = "WEB"), GraphGrouping.NONE)
        val sprawl = milestoned.nodes.associate { it.identifier to Position(15000f, 15000f) }
        val snapshot = PositionSnapshot(mapOf(mine to sprawl, sibling to sprawl))

        val plain = placeGraph(milestoned, GraphGrouping.NONE, mine, PositionSnapshot())
        val relaid = placeGraph(milestoned, GraphGrouping.NONE, mine, snapshot, relayout = true)

        assertEquals(plain.positions, relaid.positions, "re-layout did not come from layout()")
        // Saved straight away, or the next pass would inherit the sprawl back off the sibling.
        assertEquals(relaid.positions, relaid.snapshot.byKey.getValue(mine))
        val after = placeGraph(milestoned, GraphGrouping.NONE, mine, relaid.snapshot)
        assertEquals(plain.positions, after.positions, "the sprawl came back on the next pass")
    }

    @Test
    fun reLayoutAlsoClearsTheAreaOffsets() {
        val key = cacheKey(FilterOptions(team = "ENG"), GraphGrouping.MILESTONE)
        val snapshot = PositionSnapshot(mapOf(key to mapOf(groupOffsetKey("M1") to Position(300f, 55f))))
        val plain = placeGraph(milestoned, GraphGrouping.MILESTONE, key, PositionSnapshot())
        val relaid = placeGraph(milestoned, GraphGrouping.MILESTONE, key, snapshot, relayout = true)
        assertEquals(plain.positions, relaid.positions)
        assertTrue(relaid.snapshot.byKey.getValue(key).keys.none { it.startsWith("@group:") })
    }

    /** P and Q are one pile; P is the front member because its identifier sorts first. */
    private fun stacked(qMilestone: String?) = GraphData(
        nodes = listOf(
            node("A", milestone = "M1"),
            node("P", milestone = "M1"),
            node("Q", milestone = qMilestone),
            node("C", milestone = "M2"),
        ),
        edges = listOf(blocks("A", "P")),
        stacks = listOf(setOf("P", "Q")),
    )

    private val pile = stackKeyOf(setOf("P", "Q"))

    @Test
    fun aPileIsPlacedInsideItsAreaByAFreshGroupedLayout() {
        val placed = placeGraph(stacked("M1"), GraphGrouping.MILESTONE, KEY, PositionSnapshot())

        val at = placed.positions[pile]
        assertTrue(at != null, "the pile got no position: ${placed.positions.keys}")
        assertTrue(placed.positions["P"] == null, "a stacked member took a slot of its own")

        val m1 = placed.groups.first { it.label == "M1" }
        assertTrue(
            at.x >= m1.x && at.y >= m1.y && at.x <= m1.x + m1.width && at.y <= m1.y + m1.height,
            "the pile landed outside M1: $at against $m1",
        )
        assertEquals(setOf("A", pile), idsIn(stacked("M1"), GraphGrouping.MILESTONE, "M1"))
    }

    @Test
    fun aPileThatSpansTwoAreasGoesToItsFrontMember() {
        val graph = stacked("M2")
        val placed = placeGraph(graph, GraphGrouping.MILESTONE, KEY, PositionSnapshot())

        assertTrue(placed.positions[pile] != null, "the pile got no position")
        // P is the front member and sits in M1, so the pile does too. It is in M1 only.
        assertEquals("M1", slotGroups(graph, GraphGrouping.MILESTONE).getValue(pile))
        assertEquals(setOf("A", pile), idsIn(graph, GraphGrouping.MILESTONE, "M1"))
        assertEquals(setOf("C"), idsIn(graph, GraphGrouping.MILESTONE, "M2"))
    }

    @Test
    fun reLayoutPlacesThePileToo() {
        val graph = stacked("M1")
        val relaid = placeGraph(graph, GraphGrouping.MILESTONE, KEY, PositionSnapshot(), relayout = true)
        assertTrue(relaid.positions[pile] != null, "Re-layout dropped the pile")
        assertEquals(relaid.positions, relaid.snapshot.byKey.getValue(KEY))
    }

    @Test
    fun aFlatLayoutStillPlacesEveryPile() {
        val graph = stacked("M1")
        val placed = placeGraph(graph, GraphGrouping.NONE, KEY, PositionSnapshot())
        assertEquals(setOf("A", pile, "C"), placed.positions.keys)
        assertTrue(placed.groups.isEmpty())
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

    /**
     * The arrange tuck-under, wired the way `GraphScreen` wires it: the slots and edges the canvas
     * uses, fed to the layout's own rearrange. A new "A blocks B" puts B and everything under it
     * below A, against the positions on screen, and moves nothing else.
     */
    @Test
    fun aNewBlocksRelationTucksTheBlockedSubtreeUnderTheBlocker() {
        val graph = GraphData(
            nodes = listOf(node("A"), node("B"), node("C"), node("D")),
            edges = listOf(blocks("B", "C")),
        )
        // B and C sit above and to the left of A, which is where the user left them.
        val current = mapOf(
            "A" to Position(600f, 400f),
            "B" to Position(0f, 0f),
            "C" to Position(0f, 200f),
            "D" to Position(1200f, 0f),
        )
        val settled = relayoutDescendants(
            current = current,
            nodes = layoutNodesOf(graph),
            edges = layoutEdgesOf(graph),
            newEdge = LayoutEdge("A", "B", LayoutEdgeKind.BLOCKS),
        )

        assertTrue(
            settled.getValue("B").y > settled.getValue("A").y,
            "B did not move below its new blocker: ${settled["B"]}",
        )
        assertTrue(
            settled.getValue("C").y > settled.getValue("B").y,
            "C did not follow B down: ${settled["C"]}",
        )
        assertEquals(current.getValue("A"), settled.getValue("A"), "the blocker moved")
        assertEquals(current.getValue("D"), settled.getValue("D"), "an unrelated card moved")
        assertEquals(current.keys, settled.keys, "the map lost or gained a card")
    }

    // -- areas that would cover one another ----------------------------------------------------

    /** A saved layout that puts the whole of M2 inside M1: what a milestone change can leave. */
    private val overlapping = PositionSnapshot(
        mapOf(
            KEY to mapOf(
                "A" to Position(0f, 0f),
                "B" to Position(0f, 200f),
                "C" to Position(100f, 100f),
                "D" to Position(2000f, 0f),
            ),
        ),
    )

    private fun assertNoAreaCoversAnother(groups: List<GroupBox>) {
        for (i in groups.indices) {
            for (j in i + 1 until groups.size) {
                val a = groups[i]
                val b = groups[j]
                val covers = b.x < a.x + a.width && a.x < b.x + b.width &&
                    b.y < a.y + a.height && a.y < b.y + b.height
                assertTrue(!covers, "${a.label} still covers ${b.label}")
            }
        }
    }

    @Test
    fun areasThatWouldCoverEachOtherAreSeparated() {
        val before = groupBoxesOf(milestoned, GraphGrouping.MILESTONE, overlapping.byKey.getValue(KEY))
        assertTrue(
            before.first { it.label == "M2" }.x < before.first { it.label == "M1" }.x +
                before.first { it.label == "M1" }.width,
            "the fixture does not force the bad case",
        )

        val placed = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, overlapping)
        assertNoAreaCoversAnother(placed.groups)

        // Every card kept its place inside its own area, so the arrangement is untouched.
        for (box in placed.groups) {
            val was = before.first { it.label == box.label }
            idsIn(milestoned, GraphGrouping.MILESTONE, box.label).forEach { id ->
                val old = overlapping.byKey.getValue(KEY).getValue(id)
                val now = placed.positions.getValue(id)
                assertEquals(
                    Position(old.x - was.x, old.y - was.y),
                    Position(now.x - box.x, now.y - box.y),
                    "$id moved inside ${box.label}",
                )
            }
        }
    }

    @Test
    fun aSeparatedAreaIsSavedAndStaysPutOnTheNextPass() {
        val placed = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, overlapping)
        val again = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, placed.snapshot)
        assertEquals(placed.positions, again.positions, "the areas moved a second time")
        assertNoAreaCoversAnother(again.groups)
    }

    @Test
    fun separatingTheAreasIsMachinePlacementAndNotAHandEdit() {
        val placed = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, overlapping)
        assertTrue(placed.groups.size > 1)
        assertTrue(!placed.handEdited, "the machine marked the arrangement hand-edited")
        assertTrue(
            EDITED_KEY !in placed.snapshot.byKey.getValue(KEY),
            "the saved arrangement gained the hand-edited mark",
        )
    }

    @Test
    fun anOffsetForAMilestoneTheGraphNoLongerHoldsIsDropped() {
        val stale = PositionSnapshot(
            mapOf(
                KEY to mapOf(
                    groupOffsetKey("M1") to Position(0f, 40f),
                    groupOffsetKey("Gone") to Position(900f, 900f),
                ),
            ),
        )
        val placed = placeGraph(milestoned, GraphGrouping.MILESTONE, KEY, stale)
        val saved = placed.snapshot.byKey.getValue(KEY)
        assertTrue(groupOffsetKey("Gone") !in saved, "a stale area offset survived the load")
        assertEquals(Position(0f, 40f), saved.getValue(groupOffsetKey("M1")))
        assertEquals(listOf("M1", "M2", "No milestone"), placed.groups.map { it.label })
    }

    // -- the hand-edited mark -------------------------------------------------------------------

    private val twoNodes = GraphData(
        nodes = listOf(node("A"), node("B")),
        edges = listOf(blocks("A", "B")),
    )

    /** A saved arrangement carrying the mark, as the position store would hand it back. */
    private fun edited(key: String, positions: Map<String, Position>) =
        PositionSnapshot(mapOf(key to positions + (EDITED_KEY to HAND_EDITED)))

    @Test
    fun aFreshLayoutIsTheMachinesToRouteAndAHandEditedOneIsNot() {
        val fresh = placeGraph(twoNodes, GraphGrouping.NONE, KEY, PositionSnapshot())
        assertTrue(!fresh.handEdited, "a fresh machine layout came back hand-edited")

        val touched = placeGraph(twoNodes, GraphGrouping.NONE, KEY, edited(KEY, fresh.positions))
        assertTrue(touched.handEdited, "a saved arrangement lost its hand-edited mark")
    }

    /**
     * The mark rides in the position map, so it persists with the layout and comes back with it.
     * It must never reach the layout as if it were a card.
     */
    @Test
    fun theMarkSurvivesASaveAndLoadRoundTripWithoutBecomingANode() {
        val fresh = placeGraph(twoNodes, GraphGrouping.NONE, KEY, PositionSnapshot())
        val store = SettingsPositionStore(FakeSettings())
        store.set(edited(KEY, fresh.positions))

        val reloaded = store.get()
        assertContains(reloaded.byKey.getValue(KEY), EDITED_KEY)

        val placed = placeGraph(twoNodes, GraphGrouping.NONE, KEY, reloaded)
        assertTrue(placed.handEdited, "the mark did not survive the round trip")
        assertEquals(setOf("A", "B"), placed.positions.keys, "the mark was placed as a card")
        assertEquals(fresh.positions, placed.positions, "the saved arrangement moved")
    }

    @Test
    fun aRelayoutClearsTheMarkAndTheRoutesComeBack() {
        val fresh = placeGraph(twoNodes, GraphGrouping.NONE, KEY, PositionSnapshot())
        val saved = edited(KEY, fresh.positions)

        val relaid = placeGraph(twoNodes, GraphGrouping.NONE, KEY, saved, relayout = true)
        assertTrue(!relaid.handEdited, "a re-layout kept the hand-edited mark")
        assertTrue(
            EDITED_KEY !in relaid.snapshot.byKey.getValue(KEY),
            "a re-layout left the mark in the saved arrangement",
        )

        // And it stays cleared on the next ordinary pass over what the re-layout saved.
        assertTrue(
            !placeGraph(twoNodes, GraphGrouping.NONE, KEY, relaid.snapshot).handEdited,
            "the mark came back after the re-layout was saved",
        )
    }

    @Test
    fun eachArrangementCarriesItsOwnMark() {
        val flat = "team=ENG|none"
        val grouped = "team=ENG|milestone"
        val fresh = placeGraph(twoNodes, GraphGrouping.NONE, flat, PositionSnapshot())
        val snapshot = edited(flat, fresh.positions)

        assertTrue(placeGraph(twoNodes, GraphGrouping.NONE, flat, snapshot).handEdited)
        assertTrue(
            !placeGraph(twoNodes, GraphGrouping.MILESTONE, grouped, snapshot).handEdited,
            "the grouped arrangement inherited the flat one's hand-edited mark",
        )
    }

    @Test
    fun aPileOfStackedCardsTakesOneLayoutSlot() {
        val graph = GraphData(
            nodes = listOf(node("A"), node("B"), node("C"), node("D")),
            edges = listOf(blocks("A", "B"), blocks("A", "C"), blocks("B", "C")),
            stacks = listOf(setOf("B", "C")),
        )
        val nodes = layoutNodesOf(graph)
        assertEquals(listOf("A", "${STACK_PREFIX}B", "D"), nodes.map { it.id })
        val pile = nodes.single { it.id == "${STACK_PREFIX}B" }
        assertEquals(GraphCanvasDefaults.NodeWidth + STACK_OFFSET, pile.width)
        assertEquals(GraphCanvasDefaults.NodeHeight + STACK_OFFSET, pile.height)

        // A → B and A → C are the same edge once both ends run through the pile, and B → C is
        // inside it and goes nowhere.
        assertEquals(
            listOf(LayoutEdge("A", "${STACK_PREFIX}B", LayoutEdgeKind.BLOCKS)),
            layoutEdgesOf(graph),
        )

        // One position for the pile, and the placement puts it below its blocker.
        val placed = placeGraph(graph, GraphGrouping.NONE, "k", PositionSnapshot())
        assertTrue("B" !in placed.positions, "a stacked member got a position of its own")
        assertTrue(
            placed.positions.getValue("${STACK_PREFIX}B").y > placed.positions.getValue("A").y,
            "the pile was not placed below its blocker",
        )
    }

    @Test
    fun aGroupBoxHoldsTheWholePile() {
        val graph = GraphData(
            nodes = listOf(node("A", team = "ENG"), node("B", team = "ENG")),
            edges = emptyList(),
            stacks = listOf(setOf("A", "B")),
        )
        val box = groupBoxesOf(
            graph,
            GraphGrouping.TEAM,
            mapOf("${STACK_PREFIX}A" to Position(100f, 200f)),
        ).single()
        assertEquals(
            GraphCanvasDefaults.NodeWidth + STACK_OFFSET + GROUP_MARGIN * 2f,
            box.width,
        )
        // And an area drag moves the pile, which the group knows only by its slot.
        assertEquals(setOf("${STACK_PREFIX}A"), idsIn(graph, GraphGrouping.TEAM, "ENG"))
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
