package swim.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import swim.core.model.FilterOptions
import swim.core.model.IssueNode
import swim.core.model.PRIORITY_LABELS
import swim.core.model.UserSummary
import swim.core.session.FilterStore
import swim.core.session.teamKeys
import swim.ui.graph.Swim
import swim.ui.theme.SwimDimens

/** The panel expanded, and the rail it collapses to. */
internal val PANEL_WIDTH = 280.dp
internal val PANEL_RAIL = 34.dp

/** Which filter one summary chip stands for, so dismissing the chip knows what to clear. */
internal enum class FilterField { TEAM, PROJECT, LABEL, EXCLUDE, PRIORITY, STATUS, STATE, ASSIGNEE, COMPLETED }

/**
 * One filter that is set, as the panel summarises it. [value] names the one entry a chip stands
 * for when the filter holds several, which is how each team gets its own chip and its own link.
 */
internal data class ActiveFilter(
    val field: FilterField,
    val text: String,
    val value: String? = null,
)

/**
 * The filters that are set, in the order the modal lists them. This is the whole of what the
 * panel says about the query: the controls themselves live behind the Filters button.
 */
internal fun activeFilters(filters: FilterOptions): List<ActiveFilter> = buildList {
    teamKeys(filters.team).forEach { add(ActiveFilter(FilterField.TEAM, "Team: $it", it)) }
    filters.project?.let { add(ActiveFilter(FilterField.PROJECT, "Project: $it", it)) }
    filters.label?.let { add(ActiveFilter(FilterField.LABEL, "Label: $it")) }
    filters.excludeLabel?.let { add(ActiveFilter(FilterField.EXCLUDE, "Exclude: $it")) }
    filters.priority?.let {
        add(ActiveFilter(FilterField.PRIORITY, "Priority: ${PRIORITY_LABELS[it] ?: it}"))
    }
    filters.stateType?.let { add(ActiveFilter(FilterField.STATUS, "Status: $it")) }
    filters.state?.let { add(ActiveFilter(FilterField.STATE, "State: $it")) }
    filters.assignee?.let { add(ActiveFilter(FilterField.ASSIGNEE, "Assignee: $it")) }
    if (filters.includeCompleted) add(ActiveFilter(FilterField.COMPLETED, "Completed included"))
}

/**
 * Clears the one filter a summary chip stands for. A team chip clears only its own key, because
 * the team filter holds several and dismissing one must leave the rest of the query alone.
 */
internal fun clearFilter(store: FilterStore, chip: ActiveFilter) {
    when (chip.field) {
        FilterField.TEAM -> store.setTeam(
            teamKeys(store.filters.team).filterNot { it == chip.value }
                .joinToString(",").ifEmpty { null },
        )
        FilterField.PROJECT -> store.setProject(null)
        FilterField.LABEL -> store.setLabel(null)
        FilterField.EXCLUDE -> store.setExcludeLabel(null)
        FilterField.PRIORITY -> store.setPriority(null)
        FilterField.STATUS -> store.setStateType(null)
        FilterField.STATE -> store.setState(null)
        FilterField.ASSIGNEE -> store.setAssignee(null)
        FilterField.COMPLETED -> store.setIncludeCompleted(false)
    }
}

/**
 * The left panel. Collapsed it is a rail with one button; expanded it scrolls, because the
 * selection section on top pushes the view controls down as far as the selection is long.
 */
@Composable
internal fun SidePanel(
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(if (collapsed) PANEL_RAIL else PANEL_WIDTH)
                .fillMaxHeight()
                .background(Swim.Card),
        ) {
            PanelHeader(collapsed, onToggle)
            PanelRule()
            if (!collapsed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Swim.Border))
    }
}

@Composable
private fun PanelHeader(collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(SwimDimens.HeaderHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A plain glyph, never an emoji: a coloured glyph is the brightest thing in the chrome.
        IconGlyph(
            glyph = if (collapsed) "»" else "«",
            tooltip = if (collapsed) "Show the panel (⌘\\)" else "Hide the panel (⌘\\)",
            onClick = onToggle,
        )
        if (!collapsed) {
            Spacer(Modifier.width(8.dp))
            Text("Swim", color = Swim.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PanelRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Swim.Border))
}

/** A titled block. The title is the only accent-coloured text in the panel. */
@Composable
internal fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = Swim.Accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

/** A one-glyph button with a hover label. */
@Composable
internal fun IconGlyph(
    glyph: String,
    tooltip: String,
    onClick: () -> Unit,
    color: Color = Swim.TextMuted,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier = modifier) {
        Text(
            text = glyph,
            color = if (hovered) Swim.Text else color,
            fontSize = 12.sp,
            modifier = Modifier
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(glyph) { detectTapGestures { onClick() } }
                .padding(horizontal = 3.dp, vertical = 2.dp),
        )
        if (hovered) {
            Text(
                text = tooltip,
                color = Swim.Text,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = 20.dp)
                    .wrapContentSize(unbounded = true, align = Alignment.TopStart)
                    .background(Swim.Bg, RoundedCornerShape(4.dp))
                    .border(1.dp, Swim.Border, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * A summary chip. [onOpen] adds the small quick link that takes this filter's team or project
 * straight to its Linear page; the chip carries no other clutter.
 */
@Composable
internal fun FilterSummaryChip(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Swim.CardHover, RoundedCornerShape(SwimDimens.Radius))
            .border(1.dp, Swim.Border, RoundedCornerShape(SwimDimens.Radius))
            .padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Swim.Text,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        onOpen?.let { IconGlyph("↗", "Open in Linear", it) }
        IconGlyph("✕", "Clear this filter", onDismiss)
    }
}

/**
 * The section that appears on top of the panel as soon as anything is selected. It names what is
 * selected and offers the card-menu actions that make sense for a whole selection at once.
 */
@Composable
internal fun SelectionSection(
    selection: Set<String>,
    nodes: List<IssueNode>,
    users: List<UserSummary>,
    onOpenIssue: (String) -> Unit,
    onCopyId: (String) -> Unit,
    /** One confirm for the whole selection, not one per issue. */
    onAssign: (ids: Set<String>, userId: String?) -> Unit,
    onRemoveFromProject: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (selection.isEmpty()) return
    val ids = selection.sorted()
    val single = ids.singleOrNull()
    PanelSection(if (single != null) "Selected issue" else "${ids.size} selected") {
        if (single != null) {
            val node = nodes.firstOrNull { it.identifier == single }
            Text(single, color = Swim.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            node?.let {
                Text(
                    text = it.title,
                    color = Swim.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                text = ids.joinToString(", "),
                color = Swim.Cyan,
                fontSize = 11.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SwimSelect(
            label = if (single != null) "Assign" else "Assign all",
            selected = null,
            options = users.map { SwimOption(it.id, it.name) },
            onSelect = { userId -> onAssign(selection, userId) },
            width = PANEL_WIDTH - 20.dp,
            anyLabel = "Unassigned",
        )
        if (single != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(SwimDimens.Gap)) {
                SwimButton("Open in Linear", { onOpenIssue(single) })
                SwimButton("Copy ID", { onCopyId(single) })
            }
            SwimButton("Remove from project", { onRemoveFromProject(single) })
        }
        SwimButton("Clear selection", onClear)
    }
}

/** A plain full-width row of text that acts like a menu item. */
@Composable
internal fun PanelAction(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = text,
        color = if (hovered) Swim.Text else Swim.TextMuted,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (hovered) Swim.CardHover else Color.Transparent,
                RoundedCornerShape(SwimDimens.Radius),
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}
