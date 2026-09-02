package swim.ui.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import swim.core.model.GraphData
import swim.core.model.IssueNode
import swim.core.model.RelationType
import swim.core.model.UserSummary
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One relation identity the user can pick. A reversed blocks relation reads as "blocked by". */
internal data class RelationIntent(
    val label: String,
    val pickLabel: String,
    val type: RelationType,
    val reversed: Boolean,
)

internal val RELATION_INTENTS = listOf(
    RelationIntent("Blocks", "Blocks…", RelationType.BLOCKS, false),
    RelationIntent("Blocked by", "Blocked by…", RelationType.BLOCKS, true),
    RelationIntent("Related", "Related to…", RelationType.RELATED, false),
    RelationIntent("Duplicate", "Duplicate of…", RelationType.DUPLICATE, false),
)

/** The identities an existing edge can change into: everything but the one it already has. */
internal fun changeOptions(current: EdgeKey?): List<RelationIntent> = RELATION_INTENTS.filterNot {
    current != null && it.type == current.type && !it.reversed
}

/** One row of a context menu. A row with a [submenu] opens it instead of acting. */
internal class MenuEntry(
    val label: String,
    val color: Color = Swim.Text,
    val checked: Boolean = false,
    val submenu: List<MenuEntry> = emptyList(),
    val action: (() -> Unit)? = null,
)

/** How one relation of [self] reads from that issue's side. */
internal fun edgeLabel(key: EdgeKey, self: String): String = when {
    key.type == RelationType.BLOCKS && key.from == self -> "blocks ${key.to}"
    key.type == RelationType.BLOCKS -> "blocked by ${key.from}"
    key.type == RelationType.RELATED ->
        "related to ${if (key.from == self) key.to else key.from}"
    key.from == self -> "duplicate of ${key.to}"
    else -> "duplicated by ${key.from}"
}

/** Everything one context menu offers, built from what the canvas already holds. */
internal fun menuEntries(
    menu: CanvasMenu,
    graph: GraphData,
    nodes: Map<String, IssueNode>,
    users: List<UserSummary>,
    ids: List<String>,
    state: GraphCanvasState,
    callbacks: GraphCanvasCallbacks,
): List<MenuEntry> = when (menu) {
    is CanvasMenu.Node -> nodeEntries(menu.id, graph, nodes, users, state, callbacks)
    is CanvasMenu.Edge -> edgeEntries(menu.edge, callbacks)
    is CanvasMenu.Empty -> listOf(
        MenuEntry("Zoom to Fit", action = { state.fitToContent() }),
        MenuEntry("Re-layout", action = { callbacks.onRelayout() }),
        MenuEntry("Reload", action = { callbacks.onReload() }),
        MenuEntry("Select All", action = { callbacks.onSelectionChange(ids.toSet()) }),
    )
}

private fun nodeEntries(
    id: String,
    graph: GraphData,
    nodes: Map<String, IssueNode>,
    users: List<UserSummary>,
    state: GraphCanvasState,
    callbacks: GraphCanvasCallbacks,
): List<MenuEntry> {
    val assignee = nodes[id]?.assigneeId
    val relations = graph.edges.filter { it.from == id || it.to == id }.map { edge ->
        val key = edge.key()
        MenuEntry(
            label = edgeLabel(key, id),
            submenu = changeOptions(key).map { intent ->
                MenuEntry("Change to ${intent.label.lowercase()}") {
                    callbacks.onChangeRelation(key, intent.type, intent.reversed)
                }
            } + MenuEntry("Remove", color = Swim.Red) { callbacks.onRemoveRelation(key) },
        )
    }
    return listOf(
        MenuEntry("Open in Linear") { callbacks.onOpenIssue(id) },
        MenuEntry("Copy ID") { callbacks.onCopyId(id) },
        MenuEntry(
            label = "Assign to",
            submenu = listOf(
                MenuEntry("Unassign", checked = assignee == null) { callbacks.onAssign(id, null) },
            ) + users.map { user ->
                MenuEntry(user.name, checked = user.id == assignee) {
                    callbacks.onAssign(id, user.id)
                }
            },
        ),
        MenuEntry(
            label = "Add relation",
            submenu = RELATION_INTENTS.map { intent ->
                MenuEntry(intent.pickLabel) {
                    state.pick = PickTarget(id, intent.type, intent.reversed)
                }
            },
        ),
        MenuEntry(
            label = "Relations",
            submenu = relations.ifEmpty {
                listOf(MenuEntry("No relations", color = Swim.Muted))
            },
        ),
    )
}

private fun edgeEntries(edge: EdgeKey, callbacks: GraphCanvasCallbacks): List<MenuEntry> =
    changeOptions(edge).map { intent ->
        MenuEntry("Change to ${intent.label.lowercase()}") {
            callbacks.onChangeRelation(edge, intent.type, intent.reversed)
        }
    } + MenuEntry("Remove", color = Swim.Red) { callbacks.onRemoveRelation(edge) }

private val MENU_WIDTH = 184.dp
private val MENU_ROW = 22.dp
private val MENU_PADDING = 4.dp

/** The height one column of [count] rows occupies. */
private fun columnHeight(count: Int): Dp = MENU_ROW * count + MENU_PADDING * 2

/**
 * A context menu, anchored at [menu] and clamped into the viewport. This is a plain composable in
 * the canvas box, not a platform popup, so it keeps the dark theme and cannot escape the canvas.
 */
@Composable
internal fun ContextMenuSurface(
    menu: CanvasMenu,
    entries: List<MenuEntry>,
    viewportHeight: Dp,
    viewportWidth: Dp,
    density: Float,
    onDismiss: () -> Unit,
) {
    val rootHeight = columnHeight(entries.size)
    val x = menu.at.x.coerceIn(0f, max(0f, (viewportWidth - MENU_WIDTH).value * density))
    val y = menu.at.y.coerceIn(0f, max(0f, (viewportHeight - rootHeight).value * density))
    Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }) {
        MenuColumn(
            entries = entries,
            roomBelow = viewportHeight - Dp(y / density),
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun MenuColumn(entries: List<MenuEntry>, roomBelow: Dp, onDismiss: () -> Unit) {
    // Not keyed on the entries: they are rebuilt on every recomposition of the canvas, and an
    // open submenu must survive that. The child is keyed instead, below.
    var open by remember { mutableIntStateOf(-1) }
    Row {
        Column(
            modifier = Modifier
                .width(MENU_WIDTH)
                .background(Swim.Card, RoundedCornerShape(6.dp))
                .border(1.dp, Swim.Border, RoundedCornerShape(6.dp))
                // A click inside the menu must not reach the canvas and clear the selection.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = MENU_PADDING),
        ) {
            entries.forEachIndexed { index, entry ->
                MenuRow(
                    entry = entry,
                    onHover = { open = if (entry.submenu.isEmpty()) -1 else index },
                    onClick = {
                        val action = entry.action
                        if (entry.submenu.isNotEmpty()) {
                            open = index
                        } else if (action != null) {
                            action()
                            onDismiss()
                        }
                    },
                )
            }
        }
        val expanded = entries.getOrNull(open)?.takeIf { it.submenu.isNotEmpty() }
        if (expanded != null) {
            // Line the submenu up with its row, then lift it if the tail would fall off screen.
            val wanted = MENU_ROW * open
            val overflow = wanted + columnHeight(expanded.submenu.size) - roomBelow
            val shift = Dp(max(0f, min(wanted.value, (wanted - overflow).value)))
            // A fresh subtree per parent row, so one submenu does not inherit the row the
            // previous one had expanded.
            Box(Modifier.offset(y = shift)) {
                key(open) { MenuColumn(expanded.submenu, roomBelow - shift, onDismiss) }
            }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, onHover: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    val enabled = entry.action != null || entry.submenu.isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ROW)
            .background(if (hovered && enabled) Swim.Active else Color.Transparent)
            .hoverable(interaction)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .pointerInput(entry) {
                detectTapGestures(onTap = { if (enabled) onClick() })
            }
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = if (entry.checked) "✓" else " ",
            color = Swim.Text,
            fontSize = 11.sp,
            modifier = Modifier.width(13.dp),
        )
        Text(entry.label, color = entry.color, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (entry.submenu.isNotEmpty()) Text("▸", color = Swim.TextMuted, fontSize = 11.sp)
    }
}
