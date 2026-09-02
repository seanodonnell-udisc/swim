package swim.ui.graph

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every expected point list here is worked out by hand from `smoothstep-edge.ts`, with the
 * default 20 unit gap and a 0.5 step position. A card is 270 by 120.
 */
class SmoothStepTest {

    @Test
    fun aStraightRunDownStaysOnOneVerticalLine() {
        // Two cards in one column. The gapped ends and the split all sit on x = 135.
        val points = smoothStepPoints(
            source = Offset(135f, 120f),
            sourcePosition = EdgePosition.BOTTOM,
            target = Offset(135f, 300f),
            targetPosition = EdgePosition.TOP,
        )
        assertEquals(
            listOf(
                Offset(135f, 120f),
                Offset(135f, 140f),
                Offset(135f, 210f),
                Offset(135f, 210f),
                Offset(135f, 280f),
                Offset(135f, 300f),
            ),
            points,
        )
    }

    @Test
    fun aTargetAboveTheSourceWrapsAroundBothCards() {
        // The back edge of a cycle. The source direction is down but the target is up, so the
        // route leaves the bottom, crosses to the middle column, climbs past both cards, and
        // comes down onto the target top.
        val points = smoothStepPoints(
            source = Offset(135f, 520f),
            sourcePosition = EdgePosition.BOTTOM,
            target = Offset(535f, 0f),
            targetPosition = EdgePosition.TOP,
        )
        assertEquals(
            listOf(
                Offset(135f, 520f),
                Offset(135f, 540f),
                Offset(335f, 540f),
                Offset(335f, -20f),
                Offset(535f, -20f),
                Offset(535f, 0f),
            ),
            points,
        )
    }

    @Test
    fun aLateralRunStaysOnOneHorizontalLine() {
        val points = smoothStepPoints(
            source = Offset(270f, 60f),
            sourcePosition = EdgePosition.RIGHT,
            target = Offset(400f, 60f),
            targetPosition = EdgePosition.LEFT,
        )
        assertEquals(
            listOf(
                Offset(270f, 60f),
                Offset(290f, 60f),
                Offset(335f, 60f),
                Offset(335f, 60f),
                Offset(380f, 60f),
                Offset(400f, 60f),
            ),
            points,
        )
    }

    @Test
    fun twoHandlesOnTheSameSidePushTheirGapApart() {
        // Both ends leave to the left from the same x, so the added corner would land on the
        // gapped source. The gap offset of min(offset - 1, offset - 0) = 19 moves it.
        val points = smoothStepPoints(
            source = Offset(0f, 60f),
            sourcePosition = EdgePosition.LEFT,
            target = Offset(0f, 460f),
            targetPosition = EdgePosition.LEFT,
        )
        assertEquals(
            listOf(
                Offset(0f, 60f),
                Offset(-1f, 60f),
                Offset(-20f, 60f),
                Offset(-20f, 460f),
                Offset(0f, 460f),
            ),
            points,
        )
    }

    @Test
    fun everyRouteStartsOnTheSourceAndEndsOnTheTarget() {
        val source = Offset(135f, 120f)
        val target = Offset(600f, 400f)
        for (sourcePosition in EdgePosition.entries) {
            for (targetPosition in EdgePosition.entries) {
                val points =
                    smoothStepPoints(source, sourcePosition, target, targetPosition)
                assertEquals(source, points.first(), "$sourcePosition to $targetPosition")
                assertEquals(target, points.last(), "$sourcePosition to $targetPosition")
                assertTrue(points.size >= 3, "$sourcePosition to $targetPosition: $points")
            }
        }
    }

    @Test
    fun everySegmentIsOrthogonal() {
        val points = smoothStepPoints(
            source = Offset(135f, 520f),
            sourcePosition = EdgePosition.BOTTOM,
            target = Offset(535f, 0f),
            targetPosition = EdgePosition.TOP,
        )
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            assertTrue(a.x == b.x || a.y == b.y, "segment $i is diagonal: $a to $b")
        }
    }
}
