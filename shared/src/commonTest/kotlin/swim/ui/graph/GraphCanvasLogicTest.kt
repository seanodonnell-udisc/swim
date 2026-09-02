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
        val (a, b) = anchorsFor(RelationType.BLOCKS, from, to)
        assertEquals(from.bottom, a.point.y)
        assertEquals(from.center.x, a.point.x)
        assertEquals(to.top, b.point.y)
        assertEquals(to.center.x, b.point.x)
    }

    @Test
    fun sideEdgesTakeTheClosestPairOfSides() {
        val left = nodeRect(Position(0f, 0f))
        val right = nodeRect(Position(400f, 0f))
        val (a, b) = anchorsFor(RelationType.RELATED, left, right)
        assertEquals(left.right, a.point.x)
        assertEquals(right.left, b.point.x)

        val (c, d) = anchorsFor(RelationType.RELATED, right, left)
        assertEquals(right.left, c.point.x)
        assertEquals(left.right, d.point.x)
    }

    @Test
    fun stackedCardsPairTheSameSide() {
        val top = nodeRect(Position(0f, 0f))
        val below = nodeRect(Position(0f, 400f))
        val (a, b) = anchorsFor(RelationType.DUPLICATE, top, below)
        assertEquals(a.point.x, b.point.x)
    }

    @Test
    fun curveSamplesStartAndEndOnTheAnchors() {
        val from = nodeRect(Position(10f, 10f))
        val to = nodeRect(Position(500f, 400f))
        val (a, b) = anchorsFor(RelationType.BLOCKS, from, to)
        val samples = edgeSamples(a, b)
        assertEquals(a.point, samples.first())
        assertEquals(b.point, samples.last())
        assertTrue(distanceToPolyline(samples, a.point) < 0.01f)
        assertTrue(distanceToPolyline(samples, Offset(-500f, -500f)) > 100f)
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
        val rects = GraphCanvasPreview.positions.values.map { nodeRect(it) }
        val bounds = contentBoundsOf(rects)!!
        assertEquals(60f, bounds.left)
        assertEquals(40f, bounds.top)
        assertEquals(740f + GraphCanvasDefaults.NodeWidth, bounds.right)
        assertEquals(580f + GraphCanvasDefaults.NodeHeight, bounds.bottom)
        assertNull(contentBoundsOf(emptyList()))
    }

    @Test
    fun tapsNearAnEdgeHitIt() {
        val rects = GraphCanvasPreview.positions.mapValues { (_, position) -> nodeRect(position) }
        val from = rects.getValue("ENG-101")
        val to = rects.getValue("ENG-103")
        val midpoint = Offset(from.center.x, (from.bottom + to.top) / 2f)
        assertEquals(
            EdgeKey("ENG-101", "ENG-103", RelationType.BLOCKS),
            hitEdge(GraphCanvasPreview.graph, rects, midpoint, tolerance = 8f),
        )
        assertNull(hitEdge(GraphCanvasPreview.graph, rects, Offset(-400f, -400f), 8f))
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
    fun aDetourLeavesBothCardsOnTheOutwardSideAndBowsPastThem() {
        val from = Rect(0f, 400f, 270f, 520f)
        val to = Rect(0f, 0f, 270f, 120f)
        // Both cards sit left of the graph centre, so the bow goes left.
        val (a, b) = routeFor(RelationType.BLOCKS, from, to, detour = true, centerX = 800f)
        assertEquals(Offset(0f, 460f), a.point)
        assertEquals(Offset(0f, 60f), b.point)
        assertEquals(-60f, a.control?.x)
        assertEquals(-60f, b.control?.x)
        // The curve clears the cards it used to cross.
        assertTrue(edgeSamples(a, b).all { it.x <= 0.01f }, "the bow re-entered the column")
    }

    @Test
    fun aDetourBowsRightWhenTheEdgeSitsRightOfTheCentre() {
        val from = Rect(1000f, 400f, 1270f, 520f)
        val to = Rect(1000f, 0f, 1270f, 120f)
        val (a, _) = routeFor(RelationType.BLOCKS, from, to, detour = true, centerX = 500f)
        assertEquals(1270f, a.point.x)
        assertEquals(1330f, a.control?.x)
    }

    @Test
    fun routeForWithoutADetourIsTheOrdinaryAnchoring() {
        val from = Rect(0f, 0f, 270f, 120f)
        val to = Rect(0f, 300f, 270f, 420f)
        assertEquals(
            anchorsFor(RelationType.BLOCKS, from, to),
            routeFor(RelationType.BLOCKS, from, to, detour = false, centerX = 135f),
        )
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
}
