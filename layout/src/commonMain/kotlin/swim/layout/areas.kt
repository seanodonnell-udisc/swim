package swim.layout

/** One area rectangle, in canvas units. [key] names the group the rectangle draws around. */
data class AreaBox(
    val key: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Separates the area rectangles that cover one another.
 *
 * A reload can put two areas on top of each other whatever the saved layout says: an issue moves
 * to another milestone and carries its card into the neighbour, a new milestone lands on an old
 * one, an emptied milestone lets another grow over it. This runs after the cards are placed and
 * gives back the translation each area needs, keyed by [AreaBox.key], and nothing for an area
 * that stays. The caller moves the whole area by that delta, members and drag offset together,
 * so the arrangement inside an area is never touched.
 *
 * [areas] is read in order, so the caller decides which of two overlapping areas keeps its place:
 * the earlier one does. An area that covers nothing already cleared stays exactly where it is.
 * One that does not goes to the right of everything cleared so far, [gap] clear of it, and wraps
 * to a shelf below the lot once it would end past [maxRowWidth], the way [packTrees] shelves the
 * trees inside one area.
 */
fun resolveAreaOverlaps(
    areas: List<AreaBox>,
    gap: Float,
    maxRowWidth: Float,
): Map<String, Position> {
    if (areas.size < 2) return emptyMap()

    val cleared = mutableListOf<AreaBox>()
    val deltas = LinkedHashMap<String, Position>()
    var shelfTop = 0f
    var cursor = 0f
    var shelved = false

    for (area in areas) {
        if (cleared.none { covers(it, area.x, area.y, area.width, area.height) }) {
            cleared += area
            continue
        }

        // The first area to move opens a shelf at the top of the row, past the right of it.
        if (!shelved) {
            shelfTop = cleared.minOf { it.y }
            cursor = cleared.maxOf { it.x + it.width } + gap
            shelved = true
        }
        if (cursor + area.width > maxRowWidth) {
            // Below every cleared area, so the shelf starts empty however the row is arranged.
            shelfTop = cleared.maxOf { it.y + it.height } + gap
            cursor = cleared.minOf { it.x }
        }

        // An area cleared after the shelf opened can still sit in the way, so walk right past it.
        // Each step ends one area for good, as x only grows, so the walk needs no guard.
        var x = cursor
        for (step in 0..cleared.size) {
            val hit = cleared.firstOrNull { covers(it, x, shelfTop, area.width, area.height) }
                ?: break
            x = hit.x + hit.width + gap
        }

        deltas[area.key] = Position(x - area.x, shelfTop - area.y)
        cleared += area.copy(x = x, y = shelfTop)
        cursor = x + area.width + gap
    }

    return deltas
}

/** Whether [area] and the rectangle at [x], [y] share any space. Touching edges do not. */
private fun covers(area: AreaBox, x: Float, y: Float, width: Float, height: Float): Boolean =
    x < area.x + area.width && area.x < x + width &&
        y < area.y + area.height && area.y < y + height
