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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import swim.core.model.EdgeProvenance
import swim.core.model.GraphData
import swim.core.model.IssueNode
import swim.core.model.PrStatus
import swim.core.model.RelationType
import swim.core.model.StateSummary
import swim.core.model.UserSummary
import swim.layout.LayoutEdge
import swim.layout.LayoutEdgeKind
import swim.layout.Position
import swim.layout.routeEdges
import kotlin.math.exp
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
    val onSetState: (identifier: String, stateId: String, stateName: String) -> Unit = { _, _, _ -> },
    val onSetPriority: (identifier: String, priority: Int) -> Unit = { _, _ -> },
    /** A null estimate clears the points. */
    val onSetEstimate: (identifier: String, estimate: Int?) -> Unit = { _, _ -> },
    val onAttachPr: (identifier: String, url: String) -> Unit = { _, _ -> },
    val onRemoveFromProject: (identifier: String) -> Unit = {},
    /** Interact refused a card drag. The desktop beeps; every other platform stays quiet. */
    val onRefused: () -> Unit = {},
    /** The canvas context menu asks for these two; the caller owns both. */
    val onRelayout: () -> Unit = {},
    val onReload: () -> Unit = {},
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
    /** The workflow states the Status submenu offers, by team key. */
    states: Map<String, List<StateSummary>> = emptyMap(),
    // ponytail: reserved. Cycle detection is still worth having, and a future opt-in warning
    // style can read these, but every blocks edge draws the same today.
    @Suppress("UNUSED_PARAMETER") crossLinks: Set<EdgeKey> = emptySet(),
    @Suppress("UNUSED_PARAMETER") cycleEdges: Set<EdgeKey> = emptySet(),
    selection: Set<String> = emptySet(),
    state: GraphCanvasState = rememberGraphCanvasState(),
    callbacks: GraphCanvasCallbacks = GraphCanvasCallbacks(),
    underlay: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val stacks = remember(graph) { visibleStacks(graph) }
    val stackOf = remember(stacks) { stackIndex(stacks) }
    // Two cards of one pile sit on top of each other, so the edge between them has nothing to
    // draw. Dropping it here keeps the hit test and the draw on the same set of edges.
    val drawn = remember(graph, stackOf) {
        if (stackOf.isEmpty()) {
            graph
        } else {
            graph.copy(
                edges = graph.edges.filterNot {
                    stackOf[it.from] != null && stackOf[it.from] == stackOf[it.to]
                },
            )
        }
    }
    val nodes = remember(drawn) { drawn.nodes.associateBy { it.identifier } }
    val ids = remember(drawn, stacks, state.stackFront) {
        drawOrder(drawn.nodes.map { it.identifier }, stacks, state.stackFront)
    }
    val focus = remember { FocusRequester() }

    // Only the source id, so a link-drag frame does not recompose every card.
    val linkingFrom by remember(state) { derivedStateOf { state.link?.from } }

    // The routes are found against the positions on screen, not against the ones `layout`
    // produced: a saved layout, a re-layout and every drop have all moved cards since. A drag in
    // flight is not in `positions`, so the search runs at a drop and not once a frame. It is a
    // shortest path per crossing edge over a lane grid, which is worth keeping off this thread.
    var routes by remember { mutableStateOf<Map<EdgeKey, List<Position>>>(emptyMap()) }
    LaunchedEffect(drawn, positions) {
        routes = withContext(Dispatchers.Default) { routesByEdge(drawn, positions) }
    }

    val rects = buildRects(positions, ids, stackOf, state.stackFront, state.dragIds, state.dragDelta)
    // Both gestures outlive the composition that started them, so they read the current lambda
    // rather than closing over this frame's rects and selection. See the note in IssueCard.
    val onMenu = rememberUpdatedState<(Offset) -> Unit> { at ->
        state.menu = menuAt(at, state, rects, drawn, routes)
    }
    val onBoxSelect = rememberUpdatedState<(Rect) -> Unit> { box ->
        // A marquee that touches one card of a pile takes the whole pile: the pile is one unit
        // on the canvas, and half a pile cannot be dragged anywhere useful.
        callbacks.onSelectionChange(
            withStackMates(rects.filterValues { it.overlaps(box) }.keys, stackOf) +
                if (state.additive) selection else emptySet(),
        )
    }
    val bounds = contentBoundsOf(rects.values)
    SideEffect {
        state.density = density
        state.contentBounds = bounds
    }
    LaunchedEffect(bounds != null, state.viewport) {
        // This effect can run before this frame's SideEffect, so it takes the bounds directly.
        // Positions that arrive after the first frame would otherwise never arm the fit.
        state.density = density
        state.contentBounds = bounds
        state.fitOnce()
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = modifier
            .background(Swim.Bg)
            .clipToBounds()
            .onSizeChanged { state.viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event -> handleKey(event, state, callbacks) }
            .pointerHoverIcon(
                when {
                    state.pick != null -> PointerIcon.Crosshair
                    state.mode == CanvasMode.INTERACT -> PointerIcon.Hand
                    else -> PointerIcon.Default
                }
            )
            .modifierTracking(state)
            .secondaryGesture(state, onMenu)
            .pickTarget(state, rects, callbacks)
            .scrollAndZoom(state)
            // canvasTaps must come first. Its tap detector consumes the down, and the later a
            // pointer modifier is declared the earlier it sees the Main pass, so the other way
            // round the selection box never got an unconsumed down to start from.
            .canvasTaps(state, rects, drawn, routes, callbacks)
            .pinchAndBoxSelect(state, onBoxSelect),
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
            // Drawn in canvas space, under the edges and the cards. Group outlines live here.
            underlay()
            Canvas(Modifier.fillMaxSize()) {
                scale(density, pivot = Offset.Zero) {
                    // The pointer is read here, not in the composition, so a mouse move redraws
                    // the edges and never recomposes a card.
                    drawEdges(drawn, rects, routes, hoveredEdge(state, drawn, rects, routes))
                    // Both of these are affordances, not graph content, so they keep the same
                    // weight on screen at every zoom.
                    val zoom = state.scale
                    state.link?.let { drawGhostLink(rects[it.from], it.at, it.type, zoom) }
                    state.pick?.let { pick ->
                        val source = rects[pick.from]
                        drawPickRing(source, zoom)
                        state.pointer?.let {
                            drawGhostLink(source, state.toCanvas(it), pick.type, zoom)
                        }
                    }
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
                                linking = id == linkingFrom,
                                mode = state.mode,
                                onOverHandle = { state.overHandle = it },
                                shake = if (id == state.refusedId) state.refusals else 0,
                                onOpenPr = { url, title ->
                                    state.dismissPanels()
                                    state.prPanel = PrPanel(url, title, state.pointer ?: Offset.Zero)
                                },
                                prStatuses = prStatuses,
                                users = users,
                                handlers = remember(id, selection, positions, ids, stackOf, callbacks) {
                                    cardHandlers(
                                        id, state, selection, positions, ids, stackOf, callbacks,
                                    )
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
            // Over the cards, so the count on a pile is never buried by the card in front of it.
            if (stacks.isNotEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    stacks.forEach { members ->
                        val front = pileOrder(members, state.stackFront[stackKeyOf(members)]).first()
                        rects[front]?.let { key(front) { StackBadge(members, it, density) } }
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
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 36.dp),
        )

        // Bottom left, clear of the group labels. An area's label band sits above its first
        // card, so a top-left toggle covers the first label whenever the graph is fitted.
        ModeToggle(state, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 36.dp))
        HintBar(state, selection.size, Modifier.align(Alignment.BottomCenter))
        if (state.pick != null) PickHint(Modifier.align(Alignment.TopCenter))
        ModeToast(state, Modifier.align(Alignment.TopCenter))

        // A PR-derived edge has no Linear relation behind it, so both surfaces that would offer
        // to change or delete one show what derived it instead.
        state.panel?.let { panel ->
            val derived = (panel as? CanvasPanel.Edit)?.edge?.takeIf { drawn.isDerived(it) }
            if (derived == null) {
                RelationChooser(panel, state, callbacks)
            } else {
                DerivedEdgePanel(
                    lines = derivedEdgeLines(derived, nodes, prStatuses),
                    at = panel.at,
                    viewport = state.viewport,
                    density = density,
                )
            }
        }
        state.menu?.let { menu ->
            val derived = (menu as? CanvasMenu.Edge)?.edge?.takeIf { drawn.isDerived(it) }
            if (derived == null) {
                ContextMenuSurface(
                    menu = menu,
                    entries = menuEntries(menu, drawn, nodes, users, states, ids, state, callbacks),
                    viewportHeight = Dp(state.viewport.height / density),
                    viewportWidth = Dp(state.viewport.width / density),
                    density = density,
                    onDismiss = { state.menu = null },
                )
            } else {
                DerivedEdgePanel(
                    lines = derivedEdgeLines(derived, nodes, prStatuses),
                    at = menu.at,
                    viewport = state.viewport,
                    density = density,
                )
            }
        }
        state.prPanel?.let { panel ->
            PrInfoPanel(
                panel = panel,
                status = prStatuses[panel.url],
                viewport = state.viewport,
                density = density,
                onOpen = { callbacks.onOpenUrl(panel.url) },
            )
        }
        state.prUrlFor?.let { id ->
            PrUrlDialog(
                identifier = id,
                onDismiss = { state.prUrlFor = null },
                onSubmit = { url ->
                    state.prUrlFor = null
                    callbacks.onAttachPr(id, url)
                },
            )
        }
        if (state.shortcutsVisible) ShortcutsOverlay { state.shortcutsVisible = false }
    }
}

/**
 * Where every card sits. [positions] is keyed by layout slot, so one pile has one entry and
 * [cardPosition] fans it out into one rectangle per member, each offset from the card behind it.
 */
private fun buildRects(
    positions: Map<String, Position>,
    ids: List<String>,
    stackOf: Map<String, Set<String>>,
    front: Map<String, String>,
    dragIds: Set<String>,
    dragDelta: Offset,
): Map<String, Rect> = ids.mapNotNull { id ->
    val position = cardPosition(id, positions, stackOf, front) ?: return@mapNotNull null
    val shifted = if (slotOf(id, stackOf) in dragIds) {
        Position(position.x + dragDelta.x, position.y + dragDelta.y)
    } else {
        position
    }
    id to nodeRect(shifted)
}.toMap()

/** Which menu a right click at [at] opens: the card under it, the edge under it, or the canvas. */
private fun menuAt(
    at: Offset,
    state: GraphCanvasState,
    rects: Map<String, Rect>,
    graph: GraphData,
    routes: Map<EdgeKey, List<Position>>,
): CanvasMenu {
    val point = state.toCanvas(at)
    rects.entries.firstOrNull { it.value.contains(point) }
        ?.let { return CanvasMenu.Node(it.key, at) }
    hitEdge(graph, rects, point, edgeTolerance(state), routes)
        ?.let { return CanvasMenu.Edge(it, at) }
    return CanvasMenu.Empty(at)
}

/** The hit band around an edge. `toCanvas` divides by scale AND density, so this must too. */
private fun edgeTolerance(state: GraphCanvasState): Float = 8f / (state.scale * state.density)

private fun hoveredEdge(
    state: GraphCanvasState,
    graph: GraphData,
    rects: Map<String, Rect>,
    routes: Map<EdgeKey, List<Position>>,
): EdgeKey? {
    if (state.pick != null || state.link != null || state.menu != null) return null
    val at = state.pointer ?: return null
    return hitEdge(graph, rects, state.toCanvas(at), edgeTolerance(state), routes)
}

/**
 * The route for every drawn edge that needs one, keyed the way the canvas keys an edge. The
 * router works in layout slots, so a pile of stacked cards is one box and an edge onto a member
 * is an edge onto the pile, exactly as the placement saw it.
 */
private fun routesByEdge(
    graph: GraphData,
    positions: Map<String, Position>,
): Map<EdgeKey, List<Position>> {
    val nodes = layoutNodesOf(graph)
    if (nodes.size < 2) return emptyMap()
    val routed = routeEdges(nodes, positions, layoutEdgesOf(graph, duplicatesAsRelated = true))
    if (routed.isEmpty()) return emptyMap()
    val index = stackIndex(visibleStacks(graph))
    return graph.edges.mapNotNull { edge ->
        val kind = if (edge.type == RelationType.BLOCKS) {
            LayoutEdgeKind.BLOCKS
        } else {
            LayoutEdgeKind.RELATED
        }
        val slot = LayoutEdge(slotOf(edge.from, index), slotOf(edge.to, index), kind)
        routed[slot]?.let { edge.key() to it }
    }.toMap()
}

private fun cardHandlers(
    id: String,
    state: GraphCanvasState,
    selection: Set<String>,
    positions: Map<String, Position>,
    cards: List<String>,
    stackOf: Map<String, Set<String>>,
    callbacks: GraphCanvasCallbacks,
): CardHandlers {
    val mates = stackOf[id]
    val self = if (mates == null) setOf(id) else mates
    return CardHandlers(
        onSelect = {
            state.dismissPanels()
            // Only the sliver of a rear card is clickable, and the click that reaches it means
            // "let me at this one", not "act on the pile".
            if (mates != null) {
                val key = stackKeyOf(mates)
                if (pileOrder(mates, state.stackFront[key]).first() != id) {
                    state.bringToFront(key, id)
                }
            }
            // A held modifier is still building a selection, in either mode. A plain click is
            // the interact gesture: it opens the card's menu, and says so if that meant a switch.
            if (state.additive) {
                callbacks.onSelectionChange(
                    if (id in selection) selection - self else selection + self,
                )
                return@CardHandlers
            }
            if (state.mode != CanvasMode.INTERACT) state.switchTo(CanvasMode.INTERACT)
            callbacks.onSelectionChange(self)
            // The pointer is already tracked in the space every menu anchors in, and a tap is
            // always where the pointer is, so the card does not have to report its own geometry.
            state.menu = CanvasMenu.Node(id, state.pointer ?: Offset.Zero)
        },
        onOpen = { callbacks.onOpenIssue(id) },
        onDragStart = {
            state.dismissPanels()
            // Slots, not identifiers: a pile moves as the one box the layout placed.
            val moving = if (id in selection) selection else self
            state.dragIds = moving.mapTo(mutableSetOf()) { slotOf(it, stackOf) }
            state.dragDelta = Offset.Zero
        },
        onDragRefused = {
            state.dismissPanels()
            state.refuseDrag(id)
            callbacks.onRefused()
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
        onLinkStart = { side ->
            state.dismissPanels()
            val start = cardPosition(id, positions, stackOf, state.stackFront)
                ?: return@CardHandlers
            val rect = nodeRect(start)
            state.link = when (side) {
                LinkSide.BOTTOM ->
                    LinkDrag(id, Offset(rect.center.x, rect.bottom), RelationType.BLOCKS)
                LinkSide.LEFT ->
                    LinkDrag(id, Offset(rect.left, rect.center.y), RelationType.RELATED)
                LinkSide.RIGHT ->
                    LinkDrag(id, Offset(rect.right, rect.center.y), RelationType.RELATED)
            }
        },
        onLink = { delta -> state.link?.let { state.link = it.copy(at = it.at + delta) } },
        onLinkEnd = {
            val drag = state.link
            state.link = null
            if (drag == null) return@CardHandlers
            // Every card, not every slot, and last first: [cards] is in draw order, so a drop
            // on a pile names the card the user can actually see there.
            val target = cards.lastOrNull { other ->
                other != drag.from &&
                    cardPosition(other, positions, stackOf, state.stackFront)
                        ?.let { nodeRect(it).contains(drag.at) } == true
            }
            if (target != null) {
                state.panel = CanvasPanel.Create(drag.from, target, state.toScreen(drag.at))
            }
        },
    )
}

private fun handleKey(
    event: KeyEvent,
    state: GraphCanvasState,
    callbacks: GraphCanvasCallbacks,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Slash -> {
            if (!event.isShiftPressed) return false
            state.shortcutsVisible = !state.shortcutsVisible
            true
        }
        Key.Escape -> {
            val hadSurface = state.shortcutsVisible ||
                state.pick != null || state.menu != null || state.panel != null
            state.shortcutsVisible = false
            state.dismissPanels()
            if (!hadSurface) callbacks.onSelectionChange(emptySet())
            true
        }
        Key.V -> {
            state.switchTo(CanvasMode.ARRANGE)
            true
        }
        Key.I -> {
            state.switchTo(CanvasMode.INTERACT)
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
            when (event.type) {
                PointerEventType.Exit -> state.pointer = null
                else -> event.changes.lastOrNull()?.let { state.pointer = it.position }
            }
        }
    }
}

/**
 * The secondary button, taken before any card sees it, opens the context menu. Nothing drags: the
 * scroll wheel is the only way to pan, so a right press has one meaning and needs no travel guard.
 * On macOS a ctrl+click arrives here too, because Compose reports it as a secondary press.
 *
 * `awaitFirstDown` answers only to the primary button on desktop, so no other handler in the
 * canvas — including a card's tap and drag detectors — reacts to this button at all.
 */
private fun Modifier.secondaryGesture(
    state: GraphCanvasState,
    onMenu: State<(Offset) -> Unit>,
) = pointerInput(state) {
    awaitEachGesture {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
            ?: return@awaitEachGesture
        if (!event.buttons.isSecondaryPressed) return@awaitEachGesture
        down.consume()
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
        }
        onMenu.value(down.position)
    }
}

/** While a relation is waiting for its other end, the next click names it and nothing else. */
private fun Modifier.pickTarget(
    state: GraphCanvasState,
    rects: Map<String, Rect>,
    callbacks: GraphCanvasCallbacks,
) = pointerInput(state, rects) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val pick = state.pick ?: return@awaitEachGesture
        down.consume()
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
        }
        state.pick = null
        val point = state.toCanvas(down.position)
        val target = rects.entries
            .firstOrNull { (id, rect) -> id != pick.from && rect.contains(point) }?.key
        if (target != null) {
            callbacks.onCreateRelation(pick.from, target, pick.type, pick.reversed)
        }
    }
}

/**
 * Two-finger scroll pans both axes, in every mode; ctrl or cmd with scroll zooms about the
 * pointer. This is the only way to pan: nothing on the canvas drags the view.
 */
private fun Modifier.scrollAndZoom(state: GraphCanvasState) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.first()
            val delta = change.scrollDelta
            val modifiers = event.keyboardModifiers
            if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
                state.zoomBy(zoomFactor(delta.y), change.position)
            } else {
                state.panBy(Offset(-delta.x, -delta.y) * 48f)
            }
            change.consume()
        }
    }
}

/**
 * How far one scroll event zooms.
 *
 * Two things a trackpad breaks, which a wheel never did. The obvious `1 - delta * step` turns
 * NEGATIVE past a delta of about eight, and the clamp then slams the zoom to its floor in the
 * middle of a gesture; exponential is always positive, and two notches always equal one of twice
 * the size. And a flick reports a delta in the tens, which even done right would cross the whole
 * zoom range at once, so one event is worth at most [MAX_NOTCH] of them.
 */
internal fun zoomFactor(deltaY: Float): Float =
    exp(-deltaY.coerceIn(-MAX_NOTCH, MAX_NOTCH) * 0.12f)

private const val MAX_NOTCH = 4f

/**
 * A plain left drag on empty canvas draws a selection box, in Arrange only. Two pointers pinch.
 * The secondary button is handled before this, in [secondaryGesture].
 */
private fun Modifier.pinchAndBoxSelect(
    state: GraphCanvasState,
    onBoxSelect: State<(Rect) -> Unit>,
) = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val boxing = state.mode == CanvasMode.ARRANGE
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
            if (boxing) {
                state.marquee = Rect(
                    min(origin.x, change.position.x),
                    min(origin.y, change.position.y),
                    max(origin.x, change.position.x),
                    max(origin.y, change.position.y),
                )
                change.consume()
            }
        }

        val box = state.marquee
        state.marquee = null
        if (moved && boxing && !pinched && box != null) {
            onBoxSelect.value(
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
    routes: Map<EdgeKey, List<Position>>,
    callbacks: GraphCanvasCallbacks,
) = pointerInput(state, rects, routes) {
    detectTapGestures(
        onDoubleTap = { state.fitToContent() },
        onTap = { position ->
            val point = state.toCanvas(position)
            val edge = hitEdge(graph, rects, point, edgeTolerance(state), routes)
            state.dismissPanels()
            if (edge == null) {
                callbacks.onSelectionChange(emptySet())
            } else {
                // Clicking an edge is an interact gesture wherever it lands, so it switches and
                // then does the thing it would have done, rather than asking twice.
                if (state.mode != CanvasMode.INTERACT) state.switchTo(CanvasMode.INTERACT)
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
    routes: Map<EdgeKey, List<Position>> = emptyMap(),
): EdgeKey? {
    var best: EdgeKey? = null
    var bestDistance = tolerance
    for (edge in graph.edges) {
        val from = rects[edge.from] ?: continue
        val to = rects[edge.to] ?: continue
        val key = edge.key()
        // The corners, not the rounded path. A corner cuts at most the 5-unit bend radius.
        val distance = distanceToPolyline(edgePoints(edge.type, from, to, routes[key]), point)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = key
        }
    }
    return best
}

/** How far a PR-derived edge is held back from a Linear one. No dashes: the owner said so. */
internal const val DERIVED_ALPHA = 0.55f

internal fun edgeColor(type: RelationType): Color = when (type) {
    RelationType.BLOCKS -> Swim.Red
    RelationType.RELATED -> Swim.Muted
    RelationType.DUPLICATE -> Swim.Purple
}

private fun edgeDashes(type: RelationType): FloatArray? = when (type) {
    RelationType.BLOCKS -> null
    RelationType.RELATED -> floatArrayOf(5f, 5f)
    RelationType.DUPLICATE -> floatArrayOf(3f, 3f)
}

private fun DrawScope.drawEdges(
    graph: GraphData,
    rects: Map<String, Rect>,
    routes: Map<EdgeKey, List<Position>>,
    hovered: EdgeKey?,
) {
    for (edge in graph.edges) {
        val from = rects[edge.from] ?: continue
        val to = rects[edge.to] ?: continue
        val key = edge.key()
        val hover = key == hovered
        // A routed edge is drawn as the polyline the router found, corners and all. Its ends are
        // the router's, never re-derived: it may leave a card's top and meet another's bottom.
        val points = edgePoints(edge.type, from, to, routes[key])
        val arrivesAt = arrivalSide(points)
        val landsOn = points.last()
        // A derived edge is the same solid red family, held back. It is a reading of the pull
        // requests, not a relation somebody wrote down, and it must say so at a glance.
        val base = edgeColor(edge.type).let {
            if (edge.provenance == EdgeProvenance.PR_DERIVED) it.copy(alpha = DERIVED_ALPHA) else it
        }
        // A hovered edge reads brighter and thicker, so a click on it feels aimable.
        val color = if (hover) lerp(base, Color.White, 0.45f) else base
        val width = (if (edge.type == RelationType.BLOCKS) 2f else 1f) + if (hover) 2f else 0f
        drawPath(
            path = smoothStepPath(points),
            color = color,
            style = Stroke(
                width = width,
                pathEffect = edgeDashes(edge.type)?.let { PathEffect.dashPathEffect(it, 0f) },
            ),
        )
        when (edge.type) {
            RelationType.BLOCKS -> drawPath(arrowHead(landsOn, arrivesAt, 9f), color)
            RelationType.DUPLICATE -> drawPath(arrowHead(landsOn, arrivesAt, 6f), color)
            RelationType.RELATED -> Unit
        }
    }
}

/** The line a relation drag or a pick draws: the eventual edge, ghosted, on the same route. */
private fun DrawScope.drawGhostLink(from: Rect?, at: Offset, type: RelationType, zoom: Float) {
    if (from == null) return
    val blocks = type == RelationType.BLOCKS
    val start = if (blocks) {
        Offset(from.center.x, from.bottom)
    } else {
        Offset(if (at.x >= from.center.x) from.right else from.left, from.center.y)
    }
    val points = smoothStepPoints(
        source = start,
        sourcePosition = if (blocks) EdgePosition.BOTTOM else {
            if (at.x >= from.center.x) EdgePosition.RIGHT else EdgePosition.LEFT
        },
        target = at,
        targetPosition = if (blocks) EdgePosition.TOP else {
            if (at.x >= from.center.x) EdgePosition.LEFT else EdgePosition.RIGHT
        },
    )
    val unit = 1f / max(0.01f, zoom)
    drawPath(
        path = smoothStepPath(points),
        color = edgeColor(type).copy(alpha = 0.75f),
        style = Stroke(
            width = (if (blocks) 2f else 1.5f) * unit,
            pathEffect = PathEffect.dashPathEffect(
                (edgeDashes(type) ?: floatArrayOf(6f, 4f)).map { it * unit }.toFloatArray(),
                0f,
            ),
        ),
    )
}

/**
 * The ring on the card a pending relation starts from.
 *
 * ponytail: a static double ring, not the pulse the brief names. `animation-core` is not a
 * declared dependency of `:shared`, and the offscreen renderer holds every animation at frame
 * zero anyway, so a pulse would be invisible in the one place it has to be reviewed.
 */
private fun DrawScope.drawPickRing(source: Rect?, zoom: Float) {
    if (source == null) return
    val unit = 1f / max(0.01f, zoom)
    listOf(9f to 0.4f, 3f to 1f).forEach { (offset, alpha) ->
        val grow = offset * unit
        drawRoundRect(
            color = Swim.Focus.copy(alpha = alpha),
            topLeft = Offset(source.left - grow, source.top - grow),
            size = Size(source.width + grow * 2f, source.height + grow * 2f),
            cornerRadius = CornerRadius(8f + grow),
            style = Stroke(2.5f * unit),
        )
    }
}

@Composable
private fun RelationChooser(
    panel: CanvasPanel,
    state: GraphCanvasState,
    callbacks: GraphCanvasCallbacks,
) {
    val width = 132f
    val current = (panel as? CanvasPanel.Edit)?.edge
    val rows = changeOptions(current).size + if (current == null) 0 else 1
    val height = CHOOSER_ROW.value * rows + 8f
    val density = LocalDensity.current.density
    val x = panel.at.x.coerceIn(0f, max(0f, state.viewport.width - width * density))
    val y = panel.at.y.coerceIn(0f, max(0f, state.viewport.height - height * density))

    Column(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(width.dp)
            .background(Swim.Card, RoundedCornerShape(6.dp))
            .border(1.dp, Swim.Border, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
    ) {
        changeOptions(current).forEach { intent ->
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
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(CHOOSER_ROW)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 8.dp),
    ) {
        Text(text = label, color = color, fontSize = 11.sp)
    }
}

/** Fixed so the panel height is known, and so it matches a context-menu row. */
internal val CHOOSER_ROW = 22.dp

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
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(bounds) {
                fun centreOn(at: Offset) {
                    val (fit, origin) = minimapFit(bounds, size.width.toFloat(), size.height.toFloat())
                    state.centerOn(
                        Offset(
                            bounds.left + (at.x - origin.x) / fit,
                            bounds.top + (at.y - origin.y) / fit,
                        )
                    )
                }
                // The down is consumed, so the canvas underneath does not draw a marquee behind
                // the minimap. detectTapGestures leaves it unconsumed and also loses the tap.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    centreOn(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                        if (change.positionChanged()) {
                            centreOn(change.position)
                            change.consume()
                        }
                    }
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
        val view = Rect(
            map(state.toCanvas(Offset.Zero)),
            map(state.toCanvas(Offset(state.viewport.width, state.viewport.height))),
        )
        val rect = minimapViewRect(
            view = view,
            content = Rect(map(bounds.topLeft), map(bounds.bottomRight)),
            width = size.width,
            height = size.height,
            inset = 1f * density,
        )
        drawRect(Swim.Focus, rect.topLeft, rect.size, style = Stroke(1f))
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
