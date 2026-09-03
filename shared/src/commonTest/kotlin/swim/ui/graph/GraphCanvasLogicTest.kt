package swim.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import swim.core.model.PrStatus
import swim.core.model.RelationType
import swim.core.model.WorkflowStateType
import swim.layout.Position
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphCanvasLogicTest {

    private fun state(density: Float = 2f) = GraphCanvasState().apply { this.density = density }

    @Test
    fun screenAndCanvasRoundTrip() {
        val canvas = state()
        canvas.panBy(Offset(120f, -40f))
        canvas.zoomBy(1.5f, Offset(200f, 200f))
        val point = Offset(37f, 411f)
        val round = canvas.toCanvas(canvas.toScreen(point))
        assertTrue(abs(round.x - point.x) < 0.01f, "x drifted: $round")
        assertTrue(abs(round.y - point.y) < 0.01f, "y drifted: $round")
    }

    @Test
    fun zoomKeepsTheFocalPointStill() {
        val canvas = state()
        canvas.panBy(Offset(90f, 30f))
        val focus = Offset(640f, 300f)
        val before = canvas.toCanvas(focus)
        canvas.zoomBy(1.8f, focus)
        val after = canvas.toCanvas(focus)
        assertTrue(abs(before.x - after.x) < 0.01f, "focal x moved: $before to $after")
        assertTrue(abs(before.y - after.y) < 0.01f, "focal y moved: $before to $after")
    }

    @Test
    fun zoomStopsAtTheLimits() {
        val canvas = state()
        repeat(40) { canvas.zoomBy(2f, Offset.Zero) }
        assertEquals(GraphCanvasDefaults.MaxScale, canvas.scale)
        repeat(80) { canvas.zoomBy(0.5f, Offset.Zero) }
        assertEquals(GraphCanvasDefaults.MinScale, canvas.scale)
    }

    @Test
    fun blocksEdgesLeaveTheBottomAndLandOnTheTop() {
        val from = nodeRect(Position(0f, 0f))
        val to = nodeRect(Position(0f, 300f))
        val ends = edgeEnds(RelationType.BLOCKS, from, to)
        assertEquals(Offset(from.center.x, from.bottom), ends.source)
        assertEquals(EdgePosition.BOTTOM, ends.sourcePosition)
        assertEquals(Offset(to.center.x, to.top), ends.target)
        assertEquals(EdgePosition.TOP, ends.targetPosition)
    }

    @Test
    fun sideEdgesTakeTheClosestPairOfSides() {
        val left = nodeRect(Position(0f, 0f))
        val right = nodeRect(Position(400f, 0f))
        val out = edgeEnds(RelationType.RELATED, left, right)
        assertEquals(left.right, out.source.x)
        assertEquals(EdgePosition.RIGHT, out.sourcePosition)
        assertEquals(right.left, out.target.x)
        assertEquals(EdgePosition.LEFT, out.targetPosition)

        val back = edgeEnds(RelationType.RELATED, right, left)
        assertEquals(right.left, back.source.x)
        assertEquals(left.right, back.target.x)
    }

    @Test
    fun stackedCardsPairTheSameSide() {
        val top = nodeRect(Position(0f, 0f))
        val below = nodeRect(Position(0f, 400f))
        val ends = edgeEnds(RelationType.DUPLICATE, top, below)
        assertEquals(ends.source.x, ends.target.x)
        assertEquals(ends.sourcePosition, ends.targetPosition)
    }

    @Test
    fun cardCategoryFollowsTheStateName() {
        val started = WorkflowStateType.STARTED
        assertEquals(CardCategory.DONE, cardCategory("Done", started))
        assertEquals(CardCategory.DONE, cardCategory("Completed", started))
        assertEquals(CardCategory.DONE, cardCategory("Cancelled", started))
        assertEquals(CardCategory.DONE, cardCategory("Invalid", started))
        assertEquals(CardCategory.IN_PROGRESS, cardCategory("In Progress", started))
        assertEquals(CardCategory.IN_REVIEW, cardCategory("In Review", started))
        assertEquals(CardCategory.BLOCKED, cardCategory("Blocked", started))
        assertEquals(CardCategory.PAUSED, cardCategory("Paused", started))
        assertEquals(CardCategory.TODO, cardCategory("Todo", WorkflowStateType.UNSTARTED))
        assertEquals(CardCategory.DEFAULT, cardCategory("Backlog", WorkflowStateType.BACKLOG))
        assertEquals(CardCategory.DONE, cardCategory("Shipped", WorkflowStateType.COMPLETED))
    }

    @Test
    fun onlyNeutralCategoriesDimWhenNotReady() {
        assertEquals(0.4f, cardAlpha(CardCategory.DONE, ready = true))
        assertEquals(0.5f, cardAlpha(CardCategory.TODO, ready = false))
        assertEquals(1f, cardAlpha(CardCategory.TODO, ready = true))
        assertEquals(0.5f, cardAlpha(CardCategory.DEFAULT, ready = false))
        assertEquals(1f, cardAlpha(CardCategory.BLOCKED, ready = false))
    }

    @Test
    fun readySetOutranksPriorityOnTheMinimap() {
        assertEquals(androidx.compose.ui.graphics.Color.White, minimapColor(true, 1))
        assertEquals(Swim.Red, minimapColor(false, 1))
        assertEquals(Swim.Muted, minimapColor(false, 0))
    }

    @Test
    fun readyBadgeOnlyShowsForUnblockedTodo() {
        assertEquals("Ready", categoryBadge(CardCategory.TODO, ready = true))
        assertNull(categoryBadge(CardCategory.TODO, ready = false))
        assertNull(categoryBadge(CardCategory.DEFAULT, ready = true))
        assertEquals("In Progress", categoryBadge(CardCategory.IN_PROGRESS, ready = false))
    }

    @Test
    fun prChipReadsTheNumberFromTheUrl() {
        val chip = prChip(
            url = "https://github.com/swim/swim/pull/412",
            title = "Node card rows",
            status = PrStatus("APPROVED", "SUCCESS"),
        )
        assertEquals("PR #412", chip.label)
        assertEquals("✓", chip.reviewMark)
        assertEquals(Swim.Green, chip.reviewColor)
        assertEquals(Swim.Green, chip.checkColor)
        assertEquals("Node card rows · Approved · Checks passed", chip.tooltip)

        val failing = prChip(
            url = "https://github.com/swim/swim/pull/415",
            title = "Edge routing",
            status = PrStatus("CHANGES_REQUESTED", "FAILURE"),
        )
        assertEquals("±", failing.reviewMark)
        assertEquals(Swim.Red, failing.checkColor)

        val bare = prChip("https://github.com/swim/swim/pull/9", "Bare", null)
        assertNull(bare.reviewMark)
        assertNull(bare.checkColor)
        assertEquals("Bare", bare.tooltip)

        // The label reads from the same parser the fetcher uses, so it cannot name a number
        // GitHub will never be asked about.
        assertEquals("PR", prChip("https://github.com/acme/app/pull/99999999999", "Huge", null).label)
    }

    @Test
    fun contentBoundsCoverEveryCard() {
        // Every card, fanned out of its slot: the pile's three cards reach past its own position.
        val stacks = visibleStacks(GraphCanvasPreview.graph)
        val index = stackIndex(stacks)
        val rects = GraphCanvasPreview.graph.nodes.mapNotNull {
            cardPosition(it.identifier, GraphCanvasPreview.positions, index, emptyMap())
        }.map(::nodeRect)
        val bounds = contentBoundsOf(rects)!!
        assertEquals(60f, bounds.left)
        assertEquals(40f, bounds.top)
        assertEquals(740f + GraphCanvasDefaults.NodeWidth, bounds.right)
        // The pile sits at y 760 and its front card is two 14dp steps below that.
        assertEquals(760f + 2 * STACK_OFFSET + GraphCanvasDefaults.NodeHeight, bounds.bottom)
        assertNull(contentBoundsOf(emptyList()))
    }

    @Test
    fun aPileOrdersByIdentifierAndFansOutFromItsOwnPosition() {
        val members = setOf("ENG-113", "ENG-111", "ENG-112")
        assertEquals(listOf("ENG-111", "ENG-112", "ENG-113"), pileOrder(members, null))
        // The front card is the whole card, and every one behind it peeks out at the top left.
        assertEquals(2 * STACK_OFFSET, pileOffset(0, 3))
        assertEquals(0f, pileOffset(2, 3))
        assertEquals(2 * STACK_OFFSET, stackSpread(3))

        val index = stackIndex(listOf(members))
        val positions = mapOf("${STACK_PREFIX}ENG-111" to Position(100f, 200f))
        assertEquals(Position(128f, 228f), cardPosition("ENG-111", positions, index, emptyMap()))
        assertEquals(Position(100f, 200f), cardPosition("ENG-113", positions, index, emptyMap()))
    }

    @Test
    fun bringingARearCardForwardOnlyMovesThatPile() {
        val members = setOf("ENG-111", "ENG-112", "ENG-113")
        val front = mapOf("${STACK_PREFIX}ENG-111" to "ENG-113")
        assertEquals(listOf("ENG-113", "ENG-111", "ENG-112"), pileOrder(members, front.getValue("${STACK_PREFIX}ENG-111")))

        // Draw order is back to front, so the card in front is placed last and lands on top.
        val ids = listOf("ENG-101", "ENG-111", "ENG-112", "ENG-113", "ENG-102")
        assertEquals(
            listOf("ENG-101", "ENG-112", "ENG-111", "ENG-113", "ENG-102"),
            drawOrder(ids, listOf(members), front),
        )
        assertEquals(ids, drawOrder(ids, emptyList(), emptyMap()))
    }

    @Test
    fun aStackWithOnlyOneMemberLeftIsNotAPile() {
        val graph = GraphCanvasPreview.graph
        assertEquals(listOf(setOf("ENG-111", "ENG-112", "ENG-113")), visibleStacks(graph))
        // Hiding duplicates can drop members. One card is a card, not a pile.
        val thinned = graph.copy(nodes = graph.nodes.filter { it.identifier != "ENG-112" })
        assertEquals(
            listOf(setOf("ENG-111", "ENG-113")),
            visibleStacks(thinned),
        )
        val alone = graph.copy(
            nodes = graph.nodes.filter { it.identifier != "ENG-112" && it.identifier != "ENG-113" },
        )
        assertEquals(emptyList(), visibleStacks(alone))
    }

    @Test
    fun aDerivedEdgeReadsOutTheBranchAndAlwaysCarriesTheCaution() {
        val nodes = GraphCanvasPreview.graph.nodes.associateBy { it.identifier }
        val key = EdgeKey("ENG-106", "ENG-111", RelationType.BLOCKS)
        assertTrue(GraphCanvasPreview.graph.isDerived(key), "the preview lost its derived edge")

        val lines = derivedEdgeLines(key, nodes, GraphCanvasPreview.prStatuses)
        assertEquals(
            "ENG-111's PR targets ENG-106's branch `eng-106-chooser`.",
            lines[0],
        )
        assertEquals(DERIVED_CAUTION, lines[1])
        // Nothing in the caution offers to do the rebase for the user.
        assertTrue(
            "rebase" !in DERIVED_CAUTION.lowercase(),
            "the caution offers a rebase: $DERIVED_CAUTION",
        )

        // GitHub reported no branch names: the pull request numbers say the same thing.
        val numbers = derivedEdgeLines(key, nodes, emptyMap())
        assertEquals("PR #420 targets the branch of PR #419.", numbers[0])
        assertEquals(DERIVED_CAUTION, numbers[1])
    }

    @Test
    fun onlyADerivedEdgeReadsAsDerived() {
        assertTrue(
            !GraphCanvasPreview.graph.isDerived(
                EdgeKey("ENG-101", "ENG-103", RelationType.BLOCKS),
            ),
            "a Linear edge was read as derived",
        )
    }

    @Test
    fun tapsNearAnEdgeHitIt() {
        val rects = GraphCanvasPreview.positions.mapValues { (_, position) -> nodeRect(position) }
        val from = rects.getValue("ENG-101")
        val to = rects.getValue("ENG-103")
        // Below the split, where the trunk that ENG-101 shares with its other blocks edge has
        // already turned away. The shared part above the split belongs to both edges.
        val onlyThisEdge = Offset(from.center.x, to.top - 10f)
        assertEquals(
            EdgeKey("ENG-101", "ENG-103", RelationType.BLOCKS),
            hitEdge(GraphCanvasPreview.graph, rects, onlyThisEdge, tolerance = 8f),
        )
        assertNull(hitEdge(GraphCanvasPreview.graph, rects, Offset(-400f, -400f), 8f))
    }

    /**
     * A routed edge is drawn and hit along the polyline the router found, not along the plain
     * pair of anchors. Its ends are the router's: it may leave a card's top and meet a bottom.
     */
    @Test
    fun aRoutedEdgeFollowsItsWaypoints() {
        val rects = GraphCanvasPreview.positions.mapValues { (_, position) -> nodeRect(position) }
        val from = rects.getValue("ENG-101")
        val to = rects.getValue("ENG-103")
        // Out of the left side, down past the cards, and back in at the bottom.
        val route = listOf(
            Position(from.left, from.center.y),
            Position(from.left - 60f, from.center.y),
            Position(from.left - 60f, to.bottom + 40f),
            Position(to.center.x, to.bottom + 40f),
            Position(to.center.x, to.bottom),
        )
        val points = edgePoints(RelationType.BLOCKS, from, to, route)
        assertEquals(route.map { Offset(it.x, it.y) }, points, "the route was not drawn verbatim")

        // The last stretch runs upward, so the arrow lands on the target's bottom.
        assertEquals(EdgePosition.BOTTOM, arrivalSide(points))

        // A point on the detour hits the edge; the same graph without the route does not.
        val onDetour = Offset(from.left - 60f, to.bottom + 40f - 30f)
        val key = EdgeKey("ENG-101", "ENG-103", RelationType.BLOCKS)
        assertEquals(
            key,
            hitEdge(GraphCanvasPreview.graph, rects, onDetour, 8f, mapOf(key to route)),
        )
        assertNull(hitEdge(GraphCanvasPreview.graph, rects, onDetour, 8f))
    }

    /**
     * The freeze this pins: a route is a fixed polyline, so drawing one while its card is moving
     * pinned that connector in place while every plain edge followed the pointer.
     */
    @Test
    fun aRouteIsDroppedAsSoonAsEitherOfItsCardsMoves() {
        val key = EdgeKey("ENG-101", "ENG-103", RelationType.BLOCKS)
        val other = EdgeKey("ENG-102", "ENG-105", RelationType.BLOCKS)
        val positions = GraphCanvasPreview.positions
        val routing = Routing(
            byEdge = mapOf(key to listOf(Position(0f, 0f)), other to listOf(Position(1f, 1f))),
            at = positions,
        )
        val settled = emptySet<String>()

        // Nothing moving: both routes stand.
        assertEquals(
            setOf(key, other),
            routing.live(emptyMap(), positions, settled, Offset.Zero).keys,
        )

        // ENG-101 under the pointer: its edge falls back, the untouched one keeps its lanes.
        assertEquals(
            setOf(other),
            routing.live(emptyMap(), positions, setOf("ENG-101"), Offset(30f, 10f)).keys,
        )

        // A drag of zero has not moved anything yet, so nothing is dropped.
        assertEquals(
            setOf(key, other),
            routing.live(emptyMap(), positions, setOf("ENG-101"), Offset.Zero).keys,
        )

        // An area-label drag writes straight into the positions map instead, and is caught too.
        val shifted = positions + ("ENG-103" to Position(11f, 22f))
        assertEquals(
            setOf(other),
            routing.live(emptyMap(), shifted, settled, Offset.Zero).keys,
        )

        // And a pile: the members' edges route through the slot the whole pile occupies.
        val piled = Routing(
            byEdge = mapOf(EdgeKey("ENG-111", "ENG-101", RelationType.BLOCKS) to listOf(Position(0f, 0f))),
            at = positions,
        )
        val stackOf = stackIndex(visibleStacks(GraphCanvasPreview.graph))
        assertTrue(
            piled.live(stackOf, positions, setOf("${STACK_PREFIX}ENG-111"), Offset(5f, 0f)).isEmpty(),
            "a pile drag kept a stale route on one of its members",
        )
    }

    @Test
    fun anEdgeWithNoRouteIsDrawnExactlyAsBefore() {
        val rects = GraphCanvasPreview.positions.mapValues { (_, position) -> nodeRect(position) }
        val from = rects.getValue("ENG-101")
        val to = rects.getValue("ENG-103")
        assertEquals(
            edgePoints(RelationType.BLOCKS, from, to),
            edgePoints(RelationType.BLOCKS, from, to, route = null),
        )
    }

    @Test
    fun minimapFitCentresTheContent() {
        val (scale, origin) = minimapFit(Rect(0f, 0f, 400f, 200f), width = 180f, height = 120f)
        assertEquals(0.43f, scale, absoluteTolerance = 0.001f)
        assertEquals(4f, origin.x, absoluteTolerance = 0.001f)
        assertEquals(17f, origin.y, absoluteTolerance = 0.001f)
    }

    @Test
    fun fitAnchorsTheAxisThatHasRoomAndCentresTheOneThatDoesNot() {
        assertEquals(48f, fitAxis(viewport = 900f, content = 200f))
        assertEquals(-50f, fitAxis(viewport = 900f, content = 1000f))
        // Exactly the padded viewport: anchoring and centring agree.
        assertEquals(48f, fitAxis(viewport = 900f, content = 804f))
    }

    @Test
    fun fitHugsTheTopLeftWhenTheGraphIsWideAndShort() {
        val canvas = state(density = 1f)
        canvas.viewport = Size(1440f, 900f)
        canvas.contentBounds = Rect(100f, 50f, 1300f, 250f)
        canvas.fitToContent()
        // 1200x200 fits at 1:1 with room on both axes, so both edges take the padding.
        assertEquals(1f, canvas.scale, absoluteTolerance = 0.001f)
        assertEquals(48f, canvas.toScreen(Offset(100f, 50f)).x, absoluteTolerance = 0.01f)
        assertEquals(48f, canvas.toScreen(Offset(100f, 50f)).y, absoluteTolerance = 0.01f)
    }

    @Test
    fun theMinimapRectFallsBackToTheContentWhenEverythingIsInView() {
        val content = Rect(40f, 30f, 140f, 90f)
        val view = Rect(-500f, -500f, 900f, 900f)
        assertEquals(
            content,
            minimapViewRect(view, content, width = 180f, height = 120f, inset = 1f),
        )
    }

    @Test
    fun theMinimapRectClipsToTheMinimapWithAnInset() {
        val content = Rect(4f, 4f, 176f, 116f)
        val view = Rect(-30f, 20f, 90f, 400f)
        val rect = minimapViewRect(view, content, width = 180f, height = 120f, inset = 1f)
        assertEquals(Rect(1f, 20f, 90f, 119f), rect)
    }

    /**
     * The defect this pins: the old `1 - delta * 0.12` went negative past a delta of about eight,
     * which a trackpad reports all the time, and the clamp then dropped the zoom to its floor.
     */
    @Test
    fun theZoomFactorIsAlwaysPositiveAndNeverInverts() {
        val deltas = listOf(-40f, -12f, -8.4f, -1f, -0.2f, 0f, 0.2f, 1f, 8.4f, 12f, 40f)
        deltas.forEach { delta ->
            assertTrue(zoomFactor(delta) > 0f, "a delta of $delta gave a non-positive factor")
        }
        // Scrolling up zooms in, down zooms out, and never the other way about.
        assertTrue(zoomFactor(-1f) > 1f, "scrolling up did not zoom in")
        assertTrue(zoomFactor(1f) < 1f, "scrolling down did not zoom out")
        assertEquals(1f, zoomFactor(0f), absoluteTolerance = 0.0001f)
        // Bigger is never smaller: the factor rises as the delta falls, with no flip anywhere.
        deltas.zipWithNext { smaller, bigger ->
            assertTrue(
                zoomFactor(smaller) >= zoomFactor(bigger),
                "the factor rose from $smaller to $bigger",
            )
        }
        // And one flick never crosses the whole zoom range.
        assertTrue(zoomFactor(40f) > 0.5f, "one event zoomed out too far: ${zoomFactor(40f)}")
        assertTrue(zoomFactor(-40f) < 2f, "one event zoomed in too far: ${zoomFactor(-40f)}")
    }
}
