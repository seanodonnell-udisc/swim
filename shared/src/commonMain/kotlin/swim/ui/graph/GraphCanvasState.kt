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

/**
 * What a plain left drag does. Arrange moves cards and draws a selection box; Interact pans and
 * offers the relation handles. Held space pans in either one.
 */
enum class CanvasMode { ARRANGE, INTERACT }

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
    internal var pick by mutableStateOf<PickTarget?>(null)
    internal var link by mutableStateOf<LinkDrag?>(null)
    internal var dragIds by mutableStateOf<Set<String>>(emptySet())
    internal var dragDelta by mutableStateOf(Offset.Zero)

    /**
     * Where the pointer is, in screen pixels, or null before it has entered. Only the draw
     * lambdas read it, so a move redraws the edges and never recomposes a card.
     */
    internal var pointer by mutableStateOf<Offset?>(null)

    /** Whether the gestures-and-shortcuts overlay is up. The shell shows it once on first run. */
    var shortcutsVisible by mutableStateOf(false)

    /** UI state only. Every launch starts in Arrange. */
    var mode by mutableStateOf(CanvasMode.ARRANGE)

    internal var additive = false
    internal var spaceDown = false

    /** Set while the pointer rests on a relation handle, so a pan does not steal its drag. */
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
