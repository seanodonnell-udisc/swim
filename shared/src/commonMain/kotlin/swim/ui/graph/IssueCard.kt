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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import swim.core.model.IssueNode
import swim.core.model.PRIORITY_LABELS
import swim.core.model.PrStatus
import swim.core.model.UserSummary

/** What one card reports back to the canvas. Deltas arrive in canvas units. */
internal class CardHandlers(
    val onSelect: () -> Unit,
    val onOpen: () -> Unit,
    val onDragStart: () -> Unit,
    val onDrag: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onLinkStart: () -> Unit,
    val onLink: (Offset) -> Unit,
    val onLinkEnd: () -> Unit,
)

@Composable
internal fun IssueCard(
    node: IssueNode,
    ready: Boolean,
    selected: Boolean,
    simplified: Boolean,
    prStatuses: Map<String, PrStatus>,
    users: List<UserSummary>,
    handlers: CardHandlers,
    callbacks: GraphCanvasCallbacks,
    modifier: Modifier = Modifier,
) {
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

    Box(modifier = modifier) {
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
                .hoverable(interaction)
                .pointerInput(node.identifier) {
                    detectTapGestures(
                        onTap = { handlers.onSelect() },
                        onDoubleTap = { handlers.onOpen() },
                    )
                }
                .pointerInput(node.identifier) {
                    detectDragGestures(
                        onDragStart = { handlers.onDragStart() },
                        onDragEnd = { handlers.onDragEnd() },
                        onDragCancel = { handlers.onDragEnd() },
                    ) { change, amount ->
                        change.consume()
                        handlers.onDrag(amount / density)
                    }
                }
                .then(
                    if (simplified) {
                        Modifier.clip(RoundedCornerShape(6.dp)).drawBehind {
                            drawRect(
                                color = categoryColor(category),
                                size = Size(14.dp.toPx(), size.height),
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (simplified) {
                SimpleBody(node)
            } else {
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
                CardFooter(node, category, prStatuses, users, callbacks)
            }
        }

        LinkHandle(
            density = density,
            handlers = handlers,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
                .alpha(if (hovered) 1f else 0.35f),
        )
        if (hovered) CardTooltip(node.title, Modifier.align(Alignment.TopStart))
    }
}

/** What a card shows when it is too small to read: the priority dot and the identifier only. */
@Composable
private fun SimpleBody(node: IssueNode) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(start = 8.dp),
    ) {
        Box(Modifier.size(16.dp).background(priorityColor(node.priority), CircleShape))
        Text(
            text = node.identifier,
            color = Swim.Cyan,
            fontSize = 26.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
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
            PrChipView(prChip(pr.url, pr.title, prStatuses[pr.url]), callbacks)
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

@Composable
private fun PrChipView(chip: PrChip, callbacks: GraphCanvasCallbacks) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .background(Swim.Border, RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
                .hoverable(interaction)
                .pointerInput(chip.url) {
                    detectTapGestures(onTap = { callbacks.onOpenUrl(chip.url) })
                },
        ) {
            Text(chip.label, color = Swim.Accent, fontSize = 8.sp, maxLines = 1)
            chip.reviewMark?.let { Text(it, color = chip.reviewColor, fontSize = 8.sp) }
            chip.checkColor?.let { Box(Modifier.size(5.dp).background(it, CircleShape)) }
        }
        if (hovered && chip.tooltip.isNotBlank()) {
            CardTooltip(chip.tooltip, Modifier.align(Alignment.TopStart))
        }
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

@Composable
private fun LinkHandle(density: Float, handlers: CardHandlers, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(11.dp)
            .background(Swim.Red, CircleShape)
            .border(1.dp, Swim.Bg, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { handlers.onLinkStart() },
                    onDragEnd = { handlers.onLinkEnd() },
                    onDragCancel = { handlers.onLinkEnd() },
                ) { change, amount ->
                    change.consume()
                    handlers.onLink(amount / density)
                }
            },
    )
}
