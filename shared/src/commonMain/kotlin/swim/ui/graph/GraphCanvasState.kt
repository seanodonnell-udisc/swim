package swim.ui.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import swim.core.model.RelationType
import swim.layout.Position
import kotlin.math.min

/** The chooser the canvas has open, if any. Both anchor at a point in screen pixels. */
internal sealed interface CanvasPanel {
    val at: Offset

    data class Create(val from: String, val to: String, override val at: Offset) : CanvasPanel
    data class Edit(val edge: EdgeKey, override val at: Offset) : CanvasPanel
}

/** The context menu the canvas has open, if any. [at] is a point in screen pixels. */
internal sealed interface CanvasMenu {
    val at: Offset

    data class Node(val id: String, override val at: Offset) : CanvasMenu
    data class Edge(val edge: EdgeKey, override val at: Offset) : CanvasMenu
    data class Empty(override val at: Offset) : CanvasMenu
}

/** A relation drag in flight, from one of a card's handles to wherever the pointer is. */
internal data class LinkDrag(val from: String, val at: Offset, val type: RelationType)

/** The pull request the info window is reading, anchored at [at] in screen pixels. */
internal data class PrPanel(val url: String, val title: String, val at: Offset)

/** A short note over the canvas. [id] rises every time, so the same text can be said twice. */
internal data class CanvasToast(val text: String, val id: Int)

/**
 * The waypoint routes the layout found, and the positions they were found for.
 *
 * The second half is what stops a connector freezing. A route is a fixed polyline through the
 * gaps between the cards, so it is only true of the placement it was searched against. The search
 * runs off the main thread and only when [at] changes, which a drag in flight does not do, so
 * drawing a route while its card is moving pins that connector in place while every plain edge
 * follows the pointer. [live] answers with only the routes that still describe where the cards
 * are; everything else draws its plain direct line, which is built from the moving rects.
 */
internal data class Routing(
    val byEdge: Map<EdgeKey, List<Position>> = emptyMap(),
    val at: Map<String, Position> = emptyMap(),
) {
    /**
     * The routes still worth drawing, against the positions on screen right now. A drag in
     * flight is in [dragIds] and [dragDelta]; an area-label drag has already written itself into
     * [positions]. Either way a moved card drops its edges back to the direct line, and they stay
     * there until the new routes arrive, so nothing snaps through a stale shape on the way.
     */
    fun live(
        stackOf: Map<String, Set<String>>,
        positions: Map<String, Position>,
        dragIds: Set<String>,
        dragDelta: Offset,
    ): Map<EdgeKey, List<Position>> {
        if (byEdge.isEmpty()) return emptyMap()

        fun settled(id: String): Boolean {
            val slot = slotOf(id, stackOf)
            val now = positions[slot] ?: return false
            val moved = if (slot in dragIds) {
                Position(now.x + dragDelta.x, now.y + dragDelta.y)
            } else {
                now
            }
            return at[slot] == moved
        }
        return byEdge.filterKeys { settled(it.from) && settled(it.to) }
    }
}

/**
 * What a plain left drag does. Arrange moves cards, draws a selection box, and draws relations
 * from the card handles; Interact acts on what it is given and moves nothing. Scroll pans in both.
 */
enum class CanvasMode { ARRANGE, INTERACT }

/** What the toast says on the way into each mode. */
internal fun CanvasMode.label(): String = when (this) {
    CanvasMode.ARRANGE -> "Arrange"
    CanvasMode.INTERACT -> "Interact"
}

/** Pick-target mode: the relation is chosen, and the next card click names the other end. */
internal data class PickTarget(
    val from: String,
    val type: RelationType,
    val reversed: Boolean,
)

/**
 * Pan, zoom, and the transient gesture state of one canvas. Canvas space is dp; screen space is
 * pixels inside the canvas box.
 */
@Stable
class GraphCanvasState internal constructor() {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    internal var density = 1f
    internal var viewport by mutableStateOf(Size.Zero)
    internal var contentBounds by mutableStateOf<Rect?>(null)
    internal var marquee by mutableStateOf<Rect?>(null)
    internal var panel by mutableStateOf<CanvasPanel?>(null)
    internal var menu by mutableStateOf<CanvasMenu?>(null)
    internal var prPanel by mutableStateOf<PrPanel?>(null)

    /** The waypoint routes and the placement they were found for. See [Routing]. */
    internal var routing by mutableStateOf(Routing())

    /** The issue the "Link a PR by URL" input is open for, if any. */
    internal var prUrlFor by mutableStateOf<String?>(null)
    internal var pick by mutableStateOf<PickTarget?>(null)
    internal var link by mutableStateOf<LinkDrag?>(null)
    internal var dragIds by mutableStateOf<Set<String>>(emptySet())
    internal var dragDelta by mutableStateOf(Offset.Zero)

    /**
     * The card each pile shows in front, by pile key, once the user has clicked a rear one. Only
     * the piles the user has reordered are named; the rest lead with their lowest identifier.
     * This is a view of the canvas, not of the graph, so it is never persisted.
     */
    internal var stackFront by mutableStateOf<Map<String, String>>(emptyMap())

    /** Puts [id] at the front of its pile. */
    internal fun bringToFront(key: String, id: String) {
        stackFront = stackFront + (key to id)
    }

    /**
     * Where the pointer is, in screen pixels, or null before it has entered. Only the draw
     * lambdas read it, so a move redraws the edges and never recomposes a card.
     */
    internal var pointer by mutableStateOf<Offset?>(null)

    /** Whether the gestures-and-shortcuts overlay is up. The shell shows it once on first run. */
    var shortcutsVisible by mutableStateOf(false)

    /** UI state only. Every launch starts in Arrange. Switch through [switchTo], never directly. */
    var mode by mutableStateOf(CanvasMode.ARRANGE)
        private set

    /** The note the toast is showing, if any. */
    internal var toast by mutableStateOf<CanvasToast?>(null)
    private var toasts = 0

    /**
     * Switches mode and says which one, from any source: a key, the toggle, or the click in
     * Arrange that means "act on this". A switch to the mode already showing still says so, since
     * the user asked and silence would read as a dead control.
     */
    fun switchTo(next: CanvasMode) {
        mode = next
        toasts++
        toast = CanvasToast(next.label(), toasts)
    }

    /**
     * The card whose drag Interact last refused, and how many refusals there have been. The card
     * and the mode toggle both shake off the count, so a second refusal shakes again.
     */
    internal var refusedId by mutableStateOf<String?>(null)
        private set
    internal var refusals by mutableStateOf(0)
        private set

    /** Interact moves no card. The card shakes, the toggle shakes, and the caller beeps. */
    internal fun refuseDrag(id: String) {
        refusedId = id
        refusals++
    }

    internal var additive = false

    /** Set while the pointer rests on a relation handle, so a card drag does not steal it. */
    internal var overHandle = false
    private var fitted = false

    /** Screen pixels to canvas units. */
    fun toCanvas(screen: Offset): Offset = (screen - offset) / (scale * density)

    /** Canvas units to screen pixels. */
    fun toScreen(canvas: Offset): Offset = canvas * (scale * density) + offset

    fun panBy(pixels: Offset) {
        offset += pixels
    }

    /** Pans so [canvas], a point in canvas units, sits in the middle of the viewport. */
    fun centerOn(canvas: Offset) {
        if (viewport == Size.Zero) return
        panBy(viewportCenter() - toScreen(canvas))
    }

    /** Zooms about [focus], a point in screen pixels, so that point stays put. */
    fun zoomBy(factor: Float, focus: Offset) {
        val next = (scale * factor)
            .coerceIn(GraphCanvasDefaults.MinScale, GraphCanvasDefaults.MaxScale)
        if (next == scale) return
        offset = focus - (focus - offset) * (next / scale)
        scale = next
    }

    fun zoomIn() = zoomBy(1.2f, viewportCenter())

    fun zoomOut() = zoomBy(1f / 1.2f, viewportCenter())

    /** Back to 1:1 about the middle of the viewport. The zoom readout does this when clicked. */
    fun resetZoom() {
        if (scale > 0f) zoomBy(1f / scale, viewportCenter())
    }

    /**
     * Scales the graph to fit, never above 1:1. An axis with room to spare anchors to the start
     * edge with the standard padding, so a wide short graph hugs the top left instead of floating.
     */
    fun fitToContent() {
        val bounds = contentBounds ?: return
        if (viewport.width <= 0f || viewport.height <= 0f) return
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val padding = FIT_PADDING
        val fit = min(
            (viewport.width - padding * 2f) / (bounds.width * density),
            (viewport.height - padding * 2f) / (bounds.height * density),
        )
        scale = min(fit, 1f).coerceIn(GraphCanvasDefaults.MinScale, GraphCanvasDefaults.MaxScale)
        val unit = density * scale
        offset = Offset(
            fitAxis(viewport.width, bounds.width * unit),
            fitAxis(viewport.height, bounds.height * unit),
        ) - bounds.topLeft * unit
    }

    /** Closes every transient surface: the relation chooser, a context menu, and pick mode. */
    fun dismissPanels() {
        panel = null
        menu = null
        prPanel = null
        pick = null
        link = null
    }

    private fun viewportCenter() = Offset(viewport.width / 2f, viewport.height / 2f)

    internal fun fitOnce() {
        if (fitted) return
        if (contentBounds == null || viewport == Size.Zero) return
        fitted = true
        fitToContent()
    }
}

internal const val FIT_PADDING = 48f

/** Where the content starts on one axis: the padding when it fits with room, otherwise centred. */
internal fun fitAxis(viewport: Float, content: Float): Float =
    if (content < viewport - FIT_PADDING * 2f) FIT_PADDING else (viewport - content) / 2f

@Composable
fun rememberGraphCanvasState(): GraphCanvasState = remember { GraphCanvasState() }
