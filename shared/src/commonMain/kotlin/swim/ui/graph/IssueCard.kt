package swim.ui.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import swim.core.model.IssueNode
import swim.core.model.PRIORITY_LABELS
import swim.core.model.PrStatus
import swim.core.model.UserSummary

/** Which handle a relation drag started from. The side decides the relation it suggests. */
internal enum class LinkSide { BOTTOM, LEFT, RIGHT }

/** What one card reports back to the canvas. Deltas arrive in canvas units. */
internal class CardHandlers(
    val onSelect: () -> Unit,
    val onOpen: () -> Unit,
    val onDragStart: () -> Unit,
    val onDrag: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
    /** Interact will not move a card. The canvas shakes it and beeps instead. */
    val onDragRefused: () -> Unit,
    val onLinkStart: (LinkSide) -> Unit,
    val onLink: (Offset) -> Unit,
    val onLinkEnd: () -> Unit,
)

@Composable
internal fun IssueCard(
    node: IssueNode,
    ready: Boolean,
    selected: Boolean,
    /** True while a relation drag is running from this card, which keeps its handles alive. */
    linking: Boolean,
    mode: CanvasMode,
    /** Reports the pointer resting on a handle, so a card drag leaves that gesture alone. */
    onOverHandle: (Boolean) -> Unit,
    /** Rises every time Interact refuses to move this card, which shakes it once more. */
    shake: Int,
    /** A chip was clicked: open the pull-request window on it. */
    onOpenPr: (url: String, title: String) -> Unit,
    prStatuses: Map<String, PrStatus>,
    users: List<UserSummary>,
    handlers: CardHandlers,
    callbacks: GraphCanvasCallbacks,
    modifier: Modifier = Modifier,
) {
    // A pointerInput block is launched once and keeps running; Compose swaps the block only when
    // a key changes, so a closure over `handlers` freezes the selection and the positions it
    // captured on the first hover. Read the current one at gesture time instead.
    val live = rememberUpdatedState(handlers)
    val category = cardCategory(node.state, node.stateType)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val density = LocalDensity.current.density
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }

    val shakeBy = shakeOffset(shake)

    // The handles live inside the card box, and the box carries the hover, so moving onto a
    // handle cannot take the hover away and make the handle disappear under the pointer.
    Box(modifier = modifier.offset { IntOffset(shakeBy.roundToInt(), 0) }.hoverable(interaction)) {
        Column(
            modifier = Modifier
                .size(GraphCanvasDefaults.NodeWidth.dp, GraphCanvasDefaults.NodeHeight.dp)
                .alpha(cardAlpha(category, ready))
                .background(
                    if (hovered) Swim.CardHover else Swim.Card,
                    RoundedCornerShape(6.dp),
                )
                .border(
                    width = if (selected) 2.dp else categoryBorderWidth(category).dp,
                    color = if (selected) Swim.Focus else categoryBorderColor(category),
                    shape = RoundedCornerShape(6.dp),
                )
                .pointerInput(node.identifier) {
                    detectTapGestures(
                        onTap = { live.value.onSelect() },
                        onDoubleTap = { live.value.onOpen() },
                    )
                }
                .then(
                    // Only Arrange moves cards. Interact refuses, once per drag, and says so by
                    // shaking rather than by silently doing nothing.
                    if (mode == CanvasMode.ARRANGE) {
                        Modifier.pointerInput(node.identifier) {
                            detectDragGestures(
                                onDragStart = { live.value.onDragStart() },
                                onDragEnd = { live.value.onDragEnd() },
                                onDragCancel = { live.value.onDragEnd() },
                            ) { change, amount ->
                                change.consume()
                                live.value.onDrag(amount / density)
                            }
                        }
                    } else {
                        Modifier.pointerInput(node.identifier) {
                            detectDragGestures(onDragStart = { live.value.onDragRefused() }) {
                                    change, _ ->
                                change.consume()
                            }
                        }
                    },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CardHeader(node, category, ready, copied) {
                copied = true
                callbacks.onCopyId(node.identifier)
            }
            Text(
                text = node.title,
                color = Swim.Text,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            CardFooter(node, category, prStatuses, users, onOpenPr, callbacks)
        }

        // Drawing a relation is arranging the graph, so the handles live in Arrange beside the
        // card drag. A drag that leaves the card takes the hover with it, and dropping the handle
        // out of the composition would cancel the very gesture it started, so it stays while
        // linking.
        if (mode == CanvasMode.ARRANGE && (hovered || linking)) {
            LinkHandle(
                side = LinkSide.BOTTOM,
                color = Swim.Red,
                density = density,
                handlers = live,
                onOverHandle = onOverHandle,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            )
            LinkHandle(
                side = LinkSide.LEFT,
                color = Swim.Muted,
                density = density,
                handlers = live,
                onOverHandle = onOverHandle,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp),
            )
            LinkHandle(
                side = LinkSide.RIGHT,
                color = Swim.Muted,
                density = density,
                handlers = live,
                onOverHandle = onOverHandle,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
            )
        }
        if (hovered) CardTooltip(node.title, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun CardHeader(
    node: IssueNode,
    category: CardCategory,
    ready: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.size(7.dp).background(priorityColor(node.priority), CircleShape))
        Text(
            text = node.identifier,
            color = Swim.Cyan,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (copied) "✓" else "⧉",
            color = if (copied) Swim.Green else Swim.Muted,
            fontSize = 11.sp,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(node.identifier) {
                    detectTapGestures(onTap = { onCopy() })
                }
                .padding(horizontal = 1.dp),
        )
        categoryBadge(category, ready)?.let { Badge(it, categoryColor(category)) }
        Spacer(Modifier.weight(1f))
        node.estimate?.let {
            Text(text = "$it", color = Swim.TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CardFooter(
    node: IssueNode,
    category: CardCategory,
    prStatuses: Map<String, PrStatus>,
    users: List<UserSummary>,
    onOpenPr: (url: String, title: String) -> Unit,
    callbacks: GraphCanvasCallbacks,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = node.state,
            color = categoryColor(category),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 66.dp),
        )
        node.pullRequests.orEmpty().take(2).forEach { pr ->
            PrChipView(prChip(pr.url, pr.title, prStatuses[pr.url])) { onOpenPr(pr.url, pr.title) }
        }
        Spacer(Modifier.weight(1f))
        AssigneePicker(node, users, callbacks)
        Text(
            text = PRIORITY_LABELS[node.priority].orEmpty(),
            color = Swim.Muted,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 8.sp,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

/**
 * The chip is now a button, not a hover surface: everything the tooltip used to whisper lives in
 * the pull-request window, which stays open long enough to read and offers the link to GitHub.
 */
@Composable
private fun PrChipView(chip: PrChip, onOpenPr: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .background(Swim.Border, RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(chip.url) { detectTapGestures(onTap = { onOpenPr() }) },
    ) {
        Text(chip.label, color = Swim.Accent, fontSize = 8.sp, maxLines = 1)
        chip.reviewMark?.let { Text(it, color = chip.reviewColor, fontSize = 8.sp) }
        chip.checkColor?.let { Box(Modifier.size(5.dp).background(it, CircleShape)) }
    }
}

@Composable
private fun AssigneePicker(
    node: IssueNode,
    users: List<UserSummary>,
    callbacks: GraphCanvasCallbacks,
) {
    var open by remember { mutableStateOf(false) }
    val current = users.firstOrNull { it.id == node.assigneeId }
    Box {
        Text(
            text = (current?.name ?: node.assignee)?.substringBefore(' ') ?: "—",
            color = if (node.assigneeId == null) Swim.Muted else Swim.TextMuted,
            fontSize = 9.sp,
            maxLines = 1,
            modifier = Modifier
                .widthIn(max = 54.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(node.identifier) {
                    detectTapGestures(onTap = { open = true })
                },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Unassigned", fontSize = 11.sp) },
                onClick = {
                    open = false
                    callbacks.onAssign(node.identifier, null)
                },
            )
            users.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user.name, fontSize = 11.sp) },
                    onClick = {
                        open = false
                        callbacks.onAssign(node.identifier, user.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun CardTooltip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Swim.Text,
        fontSize = 10.sp,
        maxLines = 4,
        modifier = modifier
            .offset(y = (-26).dp)
            .wrapContentSize(unbounded = true, align = Alignment.TopStart)
            .widthIn(max = 260.dp)
            .background(Swim.Bg, RoundedCornerShape(4.dp))
            .border(1.dp, Swim.Border, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** A connection handle. The bottom one suggests blocks; the two sides suggest related. */
@Composable
private fun LinkHandle(
    side: LinkSide,
    color: Color,
    density: Float,
    handlers: State<CardHandlers>,
    onOverHandle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val over by interaction.collectIsHoveredAsState()
    LaunchedEffect(over) { onOverHandle(over) }
    Box(
        modifier = modifier
            .size(11.dp)
            .background(color, CircleShape)
            .border(1.dp, Swim.Bg, CircleShape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(side) {
                detectDragGestures(
                    onDragStart = { handlers.value.onLinkStart(side) },
                    onDragEnd = { handlers.value.onLinkEnd() },
                    onDragCancel = { handlers.value.onLinkEnd() },
                ) { change, amount ->
                    change.consume()
                    handlers.value.onLink(amount / density)
                }
            },
    )
}
