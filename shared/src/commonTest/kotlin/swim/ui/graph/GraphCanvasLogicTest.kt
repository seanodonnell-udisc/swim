package swim.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
        assertEquals("412", prNumber("https://github.com/swim/swim/pull/412"))
        assertEquals("7", prNumber("https://github.com/o/r/pull/7/files"))
        assertNull(prNumber("https://github.com/swim/swim/issues/412"))

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
}
