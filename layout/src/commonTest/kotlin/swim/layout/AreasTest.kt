package swim.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GAP = 120f
private const val ROW = 2600f

private fun box(key: String, x: Float, y: Float, width: Float = 400f, height: Float = 300f) =
    AreaBox(key, x, y, width, height)

private fun moved(area: AreaBox, deltas: Map<String, Position>): AreaBox {
    val delta = deltas[area.key] ?: return area
    return area.copy(x = area.x + delta.x, y = area.y + delta.y)
}

private fun covers(a: AreaBox, b: AreaBox): Boolean =
    b.x < a.x + a.width && a.x < b.x + b.width &&
        b.y < a.y + a.height && a.y < b.y + b.height

private fun assertNoneCover(areas: List<AreaBox>, deltas: Map<String, Position>) {
    val out = areas.map { moved(it, deltas) }
    for (i in out.indices) {
        for (j in i + 1 until out.size) {
            assertTrue(!covers(out[i], out[j]), "${out[i]} still covers ${out[j]}")
        }
    }
}

class AreasTest {

    @Test
    fun noAreasGiveNoDeltas() {
        assertEquals(emptyMap(), resolveAreaOverlaps(emptyList(), GAP, ROW))
        assertEquals(emptyMap(), resolveAreaOverlaps(listOf(box("M1", 0f, 0f)), GAP, ROW))
    }

    @Test
    fun areasThatCoverNothingStayWhereTheyAre() {
        val areas = listOf(box("M1", 0f, 0f), box("M2", 600f, 0f), box("M3", 1200f, 0f))
        assertEquals(emptyMap(), resolveAreaOverlaps(areas, GAP, ROW))
    }

    @Test
    fun twoAreasOnTopOfEachOtherSeparate() {
        val areas = listOf(box("M1", 0f, 0f), box("M2", 100f, 50f))
        val deltas = resolveAreaOverlaps(areas, GAP, ROW)
        assertEquals(setOf("M2"), deltas.keys)
        assertNoneCover(areas, deltas)
        // The second area goes to the right of the first, one gap clear of it.
        assertEquals(Position(420f, -50f), deltas.getValue("M2"))
    }

    @Test
    fun onlyTheAreaThatCoversAnotherMoves() {
        val areas = listOf(
            box("M1", 0f, 0f),
            box("M2", 200f, 0f),
            box("M3", 2000f, 0f),
        )
        val deltas = resolveAreaOverlaps(areas, GAP, ROW)
        assertEquals(setOf("M2"), deltas.keys)
        assertNoneCover(areas, deltas)
    }

    @Test
    fun aNewAreaLandsClearOfEveryAreaAlreadyThere() {
        // M3 arrives on top of M1 and must clear M2 as well, which is not in its way yet.
        val areas = listOf(
            box("M1", 0f, 0f),
            box("M2", 520f, 0f),
            box("M3", 60f, 20f),
        )
        val deltas = resolveAreaOverlaps(areas, GAP, ROW)
        assertEquals(setOf("M3"), deltas.keys)
        assertNoneCover(areas, deltas)
        assertEquals(1040f, 60f + deltas.getValue("M3").x)
    }

    @Test
    fun aMovedAreaWrapsToAShelfBelowPastTheRowWidth() {
        val areas = listOf(
            box("M1", 0f, 0f, width = 2400f, height = 300f),
            box("M2", 100f, 0f),
        )
        val deltas = resolveAreaOverlaps(areas, GAP, 2600f)
        val m2 = moved(areas[1], deltas)
        assertEquals(0f, m2.x, "the wrapped area did not start at the left of the row")
        assertEquals(420f, m2.y, "the wrapped area is not on a shelf below")
        assertNoneCover(areas, deltas)
    }

    @Test
    fun theSameInputAlwaysGivesTheSameAnswer() {
        val areas = listOf(
            box("M1", 0f, 0f),
            box("M2", 40f, 40f),
            box("M3", 80f, 80f),
            box("M4", 3000f, 0f),
        )
        val first = resolveAreaOverlaps(areas, GAP, ROW)
        assertEquals(first, resolveAreaOverlaps(areas, GAP, ROW))
        assertNoneCover(areas, first)
        // Running it on the answer moves nothing more.
        assertEquals(emptyMap(), resolveAreaOverlaps(areas.map { moved(it, first) }, GAP, ROW))
    }

    @Test
    fun aMovedAreaWalksPastAnAreaThatStayed() {
        // M2 moves to the right of M1. M4 then has to clear M3, which sits where it would land.
        val areas = listOf(
            box("M1", 0f, 0f),
            box("M2", 50f, 0f),
            box("M3", 1200f, 0f),
            box("M4", 60f, 0f),
        )
        val deltas = resolveAreaOverlaps(areas, GAP, ROW)
        assertEquals(setOf("M2", "M4"), deltas.keys)
        assertEquals(1720f, 60f + deltas.getValue("M4").x)
        assertNoneCover(areas, deltas)
    }
}
