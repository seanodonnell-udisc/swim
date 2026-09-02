package swim.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import swim.core.model.GraphData
import swim.core.model.IssueNode
import swim.core.model.PrStatus
import swim.core.model.RelationType
import swim.core.model.UserSummary
import swim.layout.Position
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the canvas reports. The canvas never mutates: it states an intent and the caller
 * confirms it, performs it, and feeds a new [GraphData] back in.
 */
data class GraphCanvasCallbacks(
    val onOpenIssue: (identifier: String) -> Unit = {},
    val onOpenUrl: (url: String) -> Unit = {},
    val onCopyId: (identifier: String) -> Unit = {},
    val onAssign: (identifier: String, userId: String?) -> Unit = { _, _ -> },
    val onCreateRelation: (
        from: String,
        to: String,
        type: RelationType,
        reversed: Boolean,
    ) -> Unit = { _, _, _, _ -> },
    val onChangeRelation: (
        edge: EdgeKey,
        type: RelationType,
        reversed: Boolean,
    ) -> Unit = { _, _, _ -> },
    val onRemoveRelation: (edge: EdgeKey) -> Unit = {},
    val onNodesMoved: (Map<String, Position>) -> Unit = {},
    val onSelectionChange: (Set<String>) -> Unit = {},
)

/** One entry in the relation chooser. A reversed blocks relation reads as "blocked by". */
private data class RelationIntent(
    val label: String,
    val type: RelationType,
    val reversed: Boolean,
)

private val RELATION_INTENTS = listOf(
    RelationIntent("Blocks", RelationType.BLOCKS, false),
    RelationIntent("Blocked by", RelationType.BLOCKS, true),
    RelationIntent("Related", RelationType.RELATED, false),
    RelationIntent("Duplicate", RelationType.DUPLICATE, false),
)

/**
 * The dependency graph. State in, events out: [graph] and [positions] are the caller's, and the
 * canvas keeps only what a gesture needs while it runs.
 */
@Composable
fun GraphCanvas(
    graph: GraphData,
    positions: Map<String, Position>,
    modifier: Modifier = Modifier,
    readySet: Set<String> = emptySet(),
    prStatuses: Map<String, PrStatus> = emptyMap(),
    users: List<UserSummary> = emptyList(),
    crossLinks: Set<EdgeKey> = emptySet(),
    cycleEdges: Set<EdgeKey> = emptySet(),
    selection: Set<String> = emptySet(),
    state: GraphCanvasState = rememberGraphCanvasState(),
    callbacks: GraphCanvasCallbacks = GraphCanvasCallbacks(),
) {
    val density = LocalDensity.current.density
    val nodes = remember(graph) { graph.nodes.associateBy { it.identifier } }
    val ids = remember(graph) { graph.nodes.map { it.identifier } }
    val focus = remember { FocusRequester() }

    val rects = buildRects(positions, ids, state.dragIds, state.dragDelta)
    SideEffect {
        state.density = density
        state.contentBounds = contentBoundsOf(rects.values)
    }
    LaunchedEffect(rects.isNotEmpty(), state.viewport) { state.fitOnce() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = modifier
            .background(Swim.Bg)
            .clipToBounds()
            .onSizeChanged { state.viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event -> handleKey(event.key, event.type, state, callbacks) }
            .modifierTracking(state)
            .scrollAndZoom(state)
            .panAndBoxSelect(state) { box ->
                callbacks.onSelectionChange(
                    rects.filterValues { it.overlaps(box) }.keys +
                        if (state.additive) selection else emptySet(),
                )
            }
            .canvasTaps(state, rects, graph, callbacks),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                scale(density, pivot = Offset.Zero) {
                    drawEdges(graph, rects, cycleEdges, crossLinks)
                    state.link?.let { drawLinkDrag(rects[it.from], it.at) }
                }
            }
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    ids.forEach { id ->
                        key(id) {
                            IssueCard(
                                node = nodes.getValue(id),
                                ready = id in readySet,
                                selected = id in selection,
                                prStatuses = prStatuses,
                                users = users,
                                handlers = remember(id, selection, positions, callbacks) {
                                    cardHandlers(id, state, selection, positions, callbacks)
                                },
                                callbacks = callbacks,
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(Constraints()) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEachIndexed { index, placeable ->
                        val rect = rects[ids[index]] ?: return@forEachIndexed
                        placeable.place(
                            (rect.left * density).roundToInt(),
                            (rect.top * density).roundToInt(),
                        )
                    }
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            state.marquee?.let { box ->
                drawRect(Swim.Focus.copy(alpha = 0.15f), box.topLeft, box.size)
                drawRect(Swim.Focus, box.topLeft, box.size, style = Stroke(1f * density))
            }
        }

        Minimap(
            state = state,
            rects = rects,
            nodes = nodes,
            readySet = readySet,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )

        state.panel?.let { panel -> RelationChooser(panel, state, callbacks) }
    }
}

private fun buildRects(
    positions: Map<String, Position>,
    ids: List<String>,
    dragIds: Set<String>,
    dragDelta: Offset,
): Map<String, Rect> = ids.mapNotNull { id ->
    val position = positions[id] ?: return@mapNotNull null
    val shifted = if (id in dragIds) {
        Position(position.x + dragDelta.x, position.y + dragDelta.y)
    } else {
        position
    }
    id to nodeRect(shifted)
}.toMap()

private fun cardHandlers(
    id: String,
    state: GraphCanvasState,
    selection: Set<String>,
    positions: Map<String, Position>,
    callbacks: GraphCanvasCallbacks,
) = CardHandlers(
    onSelect = {
        state.dismissPanels()
        callbacks.onSelectionChange(if (state.additive) selection + id else setOf(id))
    },
    onOpen = { callbacks.onOpenIssue(id) },
    onDragStart = {
        state.dismissPanels()
        state.dragIds = if (id in selection) selection else setOf(id)
        state.dragDelta = Offset.Zero
    },
    onDrag = { delta -> state.dragDelta += delta },
    onDragEnd = {
        val moved = state.dragIds.mapNotNull { moving ->
            val position = positions[moving] ?: return@mapNotNull null
            moving to Position(
                position.x + state.dragDelta.x,
                position.y + state.dragDelta.y,
            )
        }.toMap()
        state.dragIds = emptySet()
        state.dragDelta = Offset.Zero
        if (moved.isNotEmpty()) callbacks.onNodesMoved(moved)
    },
    onLinkStart = {
        state.dismissPanels()
        val start = positions[id] ?: return@CardHandlers
        state.link = LinkDrag(
            id,
            Offset(
                start.x + GraphCanvasDefaults.NodeWidth / 2f,
                start.y + GraphCanvasDefaults.NodeHeight,
            ),
        )
    },
    onLink = { delta -> state.link?.let { state.link = it.copy(at = it.at + delta) } },
    onLinkEnd = {
        val drag = state.link
        state.link = null
        if (drag == null) return@CardHandlers
        val target = positions.entries.firstOrNull { (other, position) ->
            other != drag.from && nodeRect(position).contains(drag.at)
        }?.key
        if (target != null) {
            state.panel = CanvasPanel.Create(drag.from, target, state.toScreen(drag.at))
        }
    },
)

private fun handleKey(
    pressed: Key,
    type: KeyEventType,
    state: GraphCanvasState,
    callbacks: GraphCanvasCallbacks,
): Boolean {
    if (pressed == Key.Spacebar) {
        state.spaceDown = type == KeyEventType.KeyDown
        return true
    }
    if (type != KeyEventType.KeyDown) return false
    return when (pressed) {
        Key.Escape -> {
            state.dismissPanels()
            callbacks.onSelectionChange(emptySet())
            true
        }
        Key.Equals, Key.Plus, Key.NumPadAdd -> {
            state.zoomIn()
            true
        }
        Key.Minus, Key.NumPadSubtract -> {
            state.zoomOut()
            true
        }
        Key.Zero, Key.NumPad0 -> {
            state.fitToContent()
            true
        }
        else -> false
    }
}

/** Records the live keyboard modifiers before any child sees the event. */
private fun Modifier.modifierTracking(state: GraphCanvasState) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val modifiers = event.keyboardModifiers
            state.additive =
                modifiers.isMetaPressed || modifiers.isCtrlPressed || modifiers.isShiftPressed
        }
    }
}

/** Two-finger scroll pans; ctrl or cmd with scroll zooms about the pointer. */
private fun Modifier.scrollAndZoom(state: GraphCanvasState) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.first()
            val delta = change.scrollDelta
            val modifiers = event.keyboardModifiers
            if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
                state.zoomBy(1f - delta.y * 0.12f, change.position)
            } else {
                state.panBy(Offset(-delta.x, -delta.y) * 48f)
            }
            change.consume()
        }
    }
}

/**
 * Middle drag, right drag, and space with left drag pan. A plain left drag on empty canvas draws
 * a selection box. Two pointers pinch.
 */
private fun Modifier.panAndBoxSelect(
    state: GraphCanvasState,
    onBoxSelect: (Rect) -> Unit,
) = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val buttons = currentEvent.buttons
        val panning = state.spaceDown || buttons.isSecondaryPressed || buttons.isTertiaryPressed
        val origin = down.position
        var centroid = origin
        var spread = 0f
        var moved = false
        var pinched = false

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                pinched = true
                state.marquee = null
                val next = (pressed[0].position + pressed[1].position) / 2f
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (spread > 0f && distance > 0f) {
                    state.zoomBy(distance / spread, next)
                    state.panBy(next - centroid)
                }
                centroid = next
                spread = distance
                pressed.forEach { it.consume() }
                continue
            }

            spread = 0f
            val change = pressed.first()
            if (change.isConsumed) break
            if (!moved &&
                (change.position - origin).getDistance() < viewConfiguration.touchSlop
            ) {
                continue
            }
            moved = true
            if (panning) {
                state.panBy(change.position - change.previousPosition)
            } else {
                state.marquee = Rect(
                    min(origin.x, change.position.x),
                    min(origin.y, change.position.y),
                    max(origin.x, change.position.x),
                    max(origin.y, change.position.y),
                )
            }
            change.consume()
        }

        val box = state.marquee
        state.marquee = null
        if (moved && !panning && !pinched && box != null) {
            onBoxSelect(
                Rect(state.toCanvas(box.topLeft), state.toCanvas(box.bottomRight)),
            )
        }
    }
}

/** A tap hits an edge, or clears the selection. A double tap fits the graph to the viewport. */
private fun Modifier.canvasTaps(
    state: GraphCanvasState,
    rects: Map<String, Rect>,
    graph: GraphData,
    callbacks: GraphCanvasCallbacks,
) = pointerInput(state, rects) {
    detectTapGestures(
        onDoubleTap = { state.fitToContent() },
        onTap = { position ->
            val point = state.toCanvas(position)
            val edge = hitEdge(graph, rects, point, 8f / state.scale)
            if (edge == null) {
                state.dismissPanels()
                callbacks.onSelectionChange(emptySet())
            } else {
                state.panel = CanvasPanel.Edit(edge, position)
            }
        },
    )
}

/** The edge nearest [point] within [tolerance] canvas units, or null. */
internal fun hitEdge(
    graph: GraphData,
    rects: Map<String, Rect>,
    point: Offset,
    tolerance: Float,
): EdgeKey? {
    var best: EdgeKey? = null
    var bestDistance = tolerance
    for (edge in graph.edges) {
        val from = rects[edge.from] ?: continue
        val to = rects[edge.to] ?: continue
        val (a, b) = anchorsFor(edge.type, from, to)
        val distance = distanceToPolyline(edgeSamples(a, b), point)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = edge.key()
        }
    }
    return best
}

private fun DrawScope.drawEdges(
    graph: GraphData,
    rects: Map<String, Rect>,
    cycleEdges: Set<EdgeKey>,
    crossLinks: Set<EdgeKey>,
) {
    for (edge in graph.edges) {
        val from = rects[edge.from] ?: continue
        val to = rects[edge.to] ?: continue
        val key = edge.key()
        val (a, b) = anchorsFor(edge.type, from, to)
        val cycle = key in cycleEdges
        val cross = key in crossLinks
        val color = when (edge.type) {
            RelationType.BLOCKS -> Swim.Red
            RelationType.RELATED -> Swim.Muted
            RelationType.DUPLICATE -> Swim.Purple
        }
        val width = when {
            cycle -> 3f
            edge.type == RelationType.BLOCKS -> 2f
            else -> 1f
        }
        val dashes = when {
            cycle || cross -> floatArrayOf(6f, 4f)
            edge.type == RelationType.RELATED -> floatArrayOf(5f, 5f)
            edge.type == RelationType.DUPLICATE -> floatArrayOf(3f, 3f)
            else -> null
        }
        drawPath(
            path = edgeCurve(a, b),
            color = color,
            style = Stroke(
                width = width,
                pathEffect = dashes?.let { PathEffect.dashPathEffect(it, 0f) },
            ),
        )
        when (edge.type) {
            RelationType.BLOCKS -> drawPath(arrowHead(b, 9f), color)
            RelationType.DUPLICATE -> drawPath(arrowHead(b, 6f), color)
            RelationType.RELATED -> Unit
        }
    }
}

private fun DrawScope.drawLinkDrag(from: Rect?, at: Offset) {
    if (from == null) return
    drawLine(
        color = Swim.Red,
        start = Offset(from.center.x, from.bottom),
        end = at,
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
    )
}

@Composable
private fun RelationChooser(
    panel: CanvasPanel,
    state: GraphCanvasState,
    callbacks: GraphCanvasCallbacks,
) {
    val width = 132f
    val height = if (panel is CanvasPanel.Edit) 132f else 116f
    val density = LocalDensity.current.density
    val x = panel.at.x.coerceIn(0f, max(0f, state.viewport.width - width * density))
    val y = panel.at.y.coerceIn(0f, max(0f, state.viewport.height - height * density))
    val current = (panel as? CanvasPanel.Edit)?.edge

    Column(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(width.dp)
            .background(Swim.Card, RoundedCornerShape(6.dp))
            .border(1.dp, Swim.Border, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
    ) {
        val options = RELATION_INTENTS.filterNot { intent ->
            current != null && intent.type == current.type && !intent.reversed
        }
        options.forEach { intent ->
            ChooserRow(intent.label, Swim.Text) {
                state.dismissPanels()
                if (current == null) {
                    val create = panel as CanvasPanel.Create
                    callbacks.onCreateRelation(
                        create.from,
                        create.to,
                        intent.type,
                        intent.reversed,
                    )
                } else {
                    callbacks.onChangeRelation(current, intent.type, intent.reversed)
                }
            }
        }
        if (current != null) {
            ChooserRow("Remove", Swim.Red) {
                state.dismissPanels()
                callbacks.onRemoveRelation(current)
            }
        }
    }
}

@Composable
private fun ChooserRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Minimap(
    state: GraphCanvasState,
    rects: Map<String, Rect>,
    nodes: Map<String, IssueNode>,
    readySet: Set<String>,
    modifier: Modifier = Modifier,
) {
    val bounds = contentBoundsOf(rects.values) ?: return
    Canvas(
        modifier = modifier
            .size(180.dp, 120.dp)
            .background(Swim.Card.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
            .border(1.dp, Swim.Border, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .pointerInput(bounds) {
                detectTapGestures { tap ->
                    val fit = minimapFit(bounds, size.width.toFloat(), size.height.toFloat())
                    val point = Offset(
                        bounds.left + (tap.x - fit.second.x) / fit.first,
                        bounds.top + (tap.y - fit.second.y) / fit.first,
                    )
                    state.panBy(
                        Offset(state.viewport.width / 2f, state.viewport.height / 2f) -
                            state.toScreen(point),
                    )
                }
            },
    ) {
        val (fit, origin) = minimapFit(bounds, size.width, size.height)
        fun map(point: Offset) = Offset(
            origin.x + (point.x - bounds.left) * fit,
            origin.y + (point.y - bounds.top) * fit,
        )
        for ((id, rect) in rects) {
            val node = nodes[id] ?: continue
            drawCircle(
                color = minimapColor(id in readySet, node.priority),
                radius = 2.5f * density,
                center = map(rect.center),
            )
        }
        val viewTopLeft = map(state.toCanvas(Offset.Zero))
        val viewBottomRight =
            map(state.toCanvas(Offset(state.viewport.width, state.viewport.height)))
        drawRect(
            color = Swim.Focus,
            topLeft = viewTopLeft,
            size = Size(
                abs(viewBottomRight.x - viewTopLeft.x),
                abs(viewBottomRight.y - viewTopLeft.y),
            ),
            style = Stroke(1f),
        )
    }
}

/** The uniform scale and top-left offset that centre [bounds] inside a minimap of this size. */
internal fun minimapFit(bounds: Rect, width: Float, height: Float): Pair<Float, Offset> {
    val padding = 4f
    val scale = min(
        (width - padding * 2f) / max(1f, bounds.width),
        (height - padding * 2f) / max(1f, bounds.height),
    )
    return scale to Offset(
        (width - bounds.width * scale) / 2f,
        (height - bounds.height * scale) / 2f,
    )
}
