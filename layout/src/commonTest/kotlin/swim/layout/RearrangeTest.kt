package swim.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun card(id: String) = LayoutNode(id, width = 130f, height = 56f)

private fun blocking(from: String, to: String) = LayoutEdge(from, to, LayoutEdgeKind.BLOCKS)

private val params = LayoutParams()

/** Two roots side by side. `B` carries a chain, a fan and a subtree, so one draw covers each. */
private val nodes = listOf("A", "A1", "B", "B1", "B2", "B1a").map { card(it) }

private val edges = listOf(
    blocking("A", "A1"),
    blocking("B", "B1"),
    blocking("B", "B2"),
    blocking("B1", "B1a"),
)

private fun placed(): Map<String, Position> = layout(nodes, edges, params).positions

private fun boxOf(id: String, at: Map<String, Position>): Box {
    val node = nodes.first { it.id == id }
    val position = at.getValue(id)
    return Box(id, position.x, position.y, position.x + node.width, position.y + node.height)
}

class RearrangeTest {
    @Test
    fun theBlockedNodeLandsUnderItsBlocker() {
        val before = placed()
        val after = relayoutDescendants(before, nodes, edges, blocking("A", "B"), params)
        val blocker = boxOf("A", after)
        val moved = boxOf("B", after)
        assertEquals(blocker.centreX, moved.centreX, 0.001f, "B must sit under the middle of A")
        assertTrue(moved.top >= blocker.bottom + params.levelGap, "B must sit one row below A")
    }

    @Test
    fun everyDescendantComesAlong() {
        val before = placed()
        val after = relayoutDescendants(before, nodes, edges, blocking("A", "B"), params)
        for (id in listOf("B", "B1", "B2", "B1a")) {
            assertTrue(after.getValue(id) != before.getValue(id), "$id must move with B")
        }
        assertTrue(boxOf("B1a", after).top > boxOf("B1", after).bottom, "the subtree keeps its shape")
        assertEquals(
            boxOf("B", after).centreX,
            (boxOf("B1", after).centreX + boxOf("B2", after).centreX) / 2f,
            0.001f,
            "the fan stays centred under B",
        )
    }

    @Test
    fun nothingElseMoves() {
        val before = placed()
        val after = relayoutDescendants(before, nodes, edges, blocking("A", "B"), params)
        assertEquals(before.keys, after.keys)
        for (id in listOf("A", "A1")) assertEquals(before.getValue(id), after.getValue(id), id)
    }

    @Test
    fun theMovedSubtreeCoversNothing() {
        val before = placed()
        val after = relayoutDescendants(before, nodes, edges, blocking("A", "B"), params)
        for (one in nodes) {
            for (other in nodes) {
                if (one.id >= other.id) continue
                assertTrue(
                    !boxOf(one.id, after).overlaps(boxOf(other.id, after)),
                    "${one.id} covers ${other.id}",
                )
            }
        }
    }

    @Test
    fun aNodeAlreadyBelowItsBlockerDoesNotMove() {
        val before = placed()
        assertEquals(before, relayoutDescendants(before, nodes, edges, blocking("A", "A1"), params))
    }

    @Test
    fun aNodeInTheWayIsSteppedOver() {
        // A1 already stands one row under A, which is where B would land.
        val before = placed()
        val after = relayoutDescendants(before, nodes, edges, blocking("A", "B"), params)
        assertTrue(
            boxOf("B", after).top >= boxOf("A1", after).bottom + params.levelGap,
            "B must clear the card already under A",
        )
    }

    @Test
    fun theSameDrawAlwaysGivesTheSameResult() {
        val before = placed()
        assertEquals(
            relayoutDescendants(before, nodes, edges, blocking("A", "B"), params),
            relayoutDescendants(before, nodes, edges, blocking("A", "B"), params),
        )
    }

    @Test
    fun anEdgeOntoANodeTheGraphDoesNotHoldChangesNothing() {
        val before = placed()
        assertEquals(before, relayoutDescendants(before, nodes, edges, blocking("A", "Z"), params))
    }
}
