package swim.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun node(id: String, width: Float = 280f) = LayoutNode(id, width, 100f)

private fun result(vararg positions: Pair<String, Position>) =
    LayoutResult(positions.toMap(), emptyList(), emptyList())

class CacheTest {
    @Test
    fun noSavedLayoutKeepsTheFreshPositions() {
        val fresh = result("A" to Position(0f, 0f), "B" to Position(0f, 10f))
        val placement = reuseAndPlace("k", fresh, listOf(node("A"), node("B")), PositionSnapshot())

        assertEquals(fresh.positions, placement.positions)
        assertFalse(placement.inherited)
        assertEquals(PositionSnapshot(), placement.updatedSnapshot)
    }

    @Test
    fun ownSavedLayoutWinsOverTheFreshOneAndIsNotRewritten() {
        val snapshot = PositionSnapshot(mapOf("k" to mapOf("A" to Position(500f, 700f))))
        val fresh = result("A" to Position(0f, 0f), "B" to Position(0f, 1000f))

        val placement = reuseAndPlace("k", fresh, listOf(node("A"), node("B")), snapshot)

        assertEquals(Position(500f, 700f), placement.positions.getValue("A"))
        // Own cache means no shift: B keeps the fresh position.
        assertEquals(Position(0f, 1000f), placement.positions.getValue("B"))
        assertFalse(placement.inherited)
        assertEquals(snapshot, placement.updatedSnapshot)
    }

    @Test
    fun anEmptyOwnEntryStillResolvesOverlapsAmongFreshNodes() {
        val snapshot = PositionSnapshot(mapOf("k" to emptyMap()))
        val fresh = result("A" to Position(0f, 0f), "B" to Position(0f, 0f))

        val placement = reuseAndPlace("k", fresh, listOf(node("A"), node("B")), snapshot)

        assertEquals(Position(0f, 0f), placement.positions.getValue("A"))
        assertEquals(Position(0f, 140f), placement.positions.getValue("B"))
        assertFalse(placement.inherited)
    }

    @Test
    fun inheritsTheSavedLayoutSharingTheMostNodes() {
        val snapshot = PositionSnapshot(
            mapOf(
                "few" to mapOf("A" to Position(0f, 0f)),
                "many" to mapOf("A" to Position(10f, 20f), "B" to Position(10f, 400f)),
            )
        )
        val fresh = result("A" to Position(0f, 0f), "B" to Position(0f, 100f))

        val placement = reuseAndPlace("k", fresh, listOf(node("A"), node("B")), snapshot)

        assertTrue(placement.inherited)
        assertEquals(Position(10f, 20f), placement.positions.getValue("A"))
        assertEquals(Position(10f, 400f), placement.positions.getValue("B"))
        assertEquals(placement.positions, placement.updatedSnapshot.byKey.getValue("k"))
        assertEquals(snapshot.byKey.getValue("many"), placement.updatedSnapshot.byKey.getValue("many"))
    }

    @Test
    fun freshNodesGoRightOfTheInheritedClusterTopAligned() {
        val snapshot = PositionSnapshot(mapOf("other" to mapOf("A" to Position(100f, 50f))))
        val fresh = result(
            "A" to Position(0f, 0f),
            "B" to Position(40f, 200f),
            "C" to Position(340f, 200f),
        )

        val placement = reuseAndPlace(
            "k",
            fresh,
            listOf(node("A"), node("B"), node("C")),
            snapshot,
        )

        // right = 100 + 280 = 380; shiftX = 380 + 120 - min(40, 340) = 460
        // top = 50; shiftY = 50 - min(200, 200) = -150
        assertEquals(Position(100f, 50f), placement.positions.getValue("A"))
        assertEquals(Position(500f, 50f), placement.positions.getValue("B"))
        assertEquals(Position(800f, 50f), placement.positions.getValue("C"))
    }

    @Test
    fun savedNodesNeverMoveAndAutoNodesOnlyMoveDown() {
        val snapshot = PositionSnapshot(mapOf("other" to mapOf("A" to Position(0f, 0f))))
        // A gap of zero puts the fresh node inside the saved node's collision box.
        val fresh = result("A" to Position(0f, 0f), "B" to Position(0f, 0f))

        val placement = reuseAndPlace(
            "k",
            fresh,
            listOf(node("A"), node("B")),
            snapshot,
            freshGap = 0f,
        )

        assertEquals(Position(0f, 0f), placement.positions.getValue("A"))
        // shiftX = 0 + 280 + 0 - 0 = 280, which still collides, so B alone moves down.
        assertEquals(Position(280f, 140f), placement.positions.getValue("B"))
    }

    @Test
    fun overlapsCascadeDownOneCollisionHeightAtATime() {
        val fresh = result(
            "A" to Position(0f, 0f),
            "B" to Position(0f, 0f),
            "C" to Position(0f, 0f),
        )
        val snapshot = PositionSnapshot(mapOf("k" to emptyMap()))

        val placement = reuseAndPlace("k", fresh, listOf(node("A"), node("B"), node("C")), snapshot)

        assertEquals(Position(0f, 0f), placement.positions.getValue("A"))
        assertEquals(Position(0f, 140f), placement.positions.getValue("B"))
        assertEquals(Position(0f, 280f), placement.positions.getValue("C"))
    }

    @Test
    fun nodesFurtherApartThanTheCollisionBoxDoNotMove() {
        val fresh = result("A" to Position(0f, 0f), "B" to Position(300f, 0f))
        val snapshot = PositionSnapshot(mapOf("k" to emptyMap()))

        val placement = reuseAndPlace("k", fresh, listOf(node("A"), node("B")), snapshot)

        assertEquals(fresh.positions, placement.positions)
    }

    @Test
    fun placementIsDeterministic() {
        val snapshot = PositionSnapshot(
            mapOf(
                "one" to mapOf("A" to Position(0f, 0f), "B" to Position(5f, 5f)),
                "two" to mapOf("B" to Position(90f, 90f)),
            )
        )
        val nodes = listOf(node("A"), node("B"), node("C"), node("D"))
        val fresh = result(
            "A" to Position(0f, 0f),
            "B" to Position(1f, 1f),
            "C" to Position(2f, 2f),
            "D" to Position(3f, 3f),
        )

        val first = reuseAndPlace("k", fresh, nodes, snapshot)
        val second = reuseAndPlace("k", fresh, nodes, snapshot)

        assertEquals(first, second)
    }
}
