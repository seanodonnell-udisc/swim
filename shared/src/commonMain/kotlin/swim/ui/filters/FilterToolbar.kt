package swim.ui.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import swim.core.model.FilterOptions
import swim.core.model.LabelSummary
import swim.core.model.PRIORITY_LABELS
import swim.core.model.ProjectSummary
import swim.core.model.TeamSummary
import swim.core.model.UserSummary
import swim.core.session.Availables
import swim.core.session.FilterStore
import swim.core.session.availableLabels
import swim.core.session.availableProjects
import swim.core.session.availableTeams
import swim.core.session.teamKeys
import swim.ui.app.SwimButton
import swim.ui.app.SwimCheckbox
import swim.ui.app.SwimMultiSelect
import swim.ui.app.SwimOption
import swim.ui.app.SwimSelect
import swim.ui.app.SwimTextField
import swim.ui.graph.Swim
import swim.ui.theme.SwimDimens

/** The workspace lists the filter bar picks from. Empty until the first reference load answers. */
data class ReferenceData(
    val teams: List<TeamSummary> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val labels: List<LabelSummary> = emptyList(),
    val users: List<UserSummary> = emptyList(),
    /** The workspace slug in a Linear URL. Empty until the reference load answers. */
    val urlKey: String = "",
)

/** The status types Linear has, worded as the legacy filter bar worded them. */
internal val STATE_TYPE_OPTIONS: List<SwimOption> = listOf(
    SwimOption("backlog", "Backlog"),
    SwimOption("unstarted", "Todo"),
    SwimOption("started", "In Progress"),
    SwimOption("completed", "Completed"),
    SwimOption("canceled", "Canceled"),
)

internal val PRIORITY_OPTIONS: List<SwimOption> =
    (1..4).map { SwimOption(it.toString(), PRIORITY_LABELS.getValue(it)) } +
        SwimOption("0", PRIORITY_LABELS.getValue(0))

/** Narrows every option list by the selections in the other filters. */
fun availablesOf(reference: ReferenceData, filters: FilterOptions): Availables {
    val selected = teamKeys(filters.team)
    val project = reference.projects.firstOrNull { it.name == filters.project }
    return Availables(
        teams = availableTeams(reference.teams, project),
        projects = availableProjects(reference.projects, selected),
        labels = availableLabels(reference.labels, selected),
    )
}

/** Splits a comma-joined filter value. */
internal fun commaValues(value: String?): List<String> =
    value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** Joins a multi-select back into the comma-joined form the filters store. */
internal fun commaJoin(values: List<String>): String? =
    values.joinToString(",").ifEmpty { null }

/** The load button reads Reload only once a load has already answered for these filters. */
internal fun loadButtonLabel(loaded: Boolean, armed: Boolean): String =
    if (loaded && !armed) "Reload" else "Load issues"

/** Clear filters only appears once a filter is set. */
internal fun clearVisible(filters: FilterOptions): Boolean = filters != FilterOptions()

/**
 * The whole filter set, behind one button. Every control writes straight through [store], so an
 * edit here arms a load; only Apply runs it. The panel keeps nothing but the summary chips.
 */
@Composable
internal fun FiltersDialog(
    filters: FilterOptions,
    availables: Availables,
    store: FilterStore,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(FILTERS_DIALOG_WIDTH)
                .background(Swim.Card, RoundedCornerShape(8.dp))
                .border(1.dp, Swim.Border, RoundedCornerShape(8.dp))
                // The backdrop takes every click that lands between the controls otherwise, and
                // closes the dialog under the pointer.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Filters", color = Swim.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)

            FilterRow {
                SwimMultiSelect(
                    label = "Team",
                    selected = teamKeys(filters.team),
                    options = availables.teams.map { SwimOption(it.key, "${it.key} · ${it.name}") },
                    onChange = { store.setTeam(commaJoin(it)) },
                    width = FIELD,
                )
                SwimSelect(
                    label = "Project",
                    selected = filters.project,
                    options = availables.projects.map { SwimOption(it.name, it.name) },
                    onSelect = { name ->
                        store.setProject(name, availables.projects.firstOrNull { it.name == name }?.id)
                    },
                    width = FIELD,
                )
            }
            FilterRow {
                SwimSelect(
                    label = "Label",
                    selected = filters.label,
                    options = availables.labels.map { SwimOption(it.name, it.name) },
                    onSelect = store::setLabel,
                    width = FIELD,
                )
                SwimSelect(
                    label = "Exclude",
                    selected = filters.excludeLabel,
                    options = availables.labels.map { SwimOption(it.name, it.name) },
                    onSelect = store::setExcludeLabel,
                    width = FIELD,
                )
            }
            FilterRow {
                SwimSelect(
                    label = "Priority",
                    selected = filters.priority?.toString(),
                    options = PRIORITY_OPTIONS,
                    onSelect = { store.setPriority(it?.toIntOrNull()) },
                    width = FIELD,
                )
                SwimMultiSelect(
                    label = "Status",
                    selected = commaValues(filters.stateType),
                    options = STATE_TYPE_OPTIONS,
                    onChange = { store.setStateType(commaJoin(it)) },
                    width = FIELD,
                )
            }
            FilterRow {
                SwimTextField(
                    value = filters.state.orEmpty(),
                    onValueChange = { store.setState(it.ifBlank { null }) },
                    placeholder = "State",
                    width = FIELD,
                )
                SwimTextField(
                    value = filters.assignee.orEmpty(),
                    onValueChange = { store.setAssignee(it.ifBlank { null }) },
                    placeholder = "Assignee",
                    width = FIELD,
                )
            }
            SwimCheckbox(
                label = "Include completed",
                checked = filters.includeCompleted,
                onCheckedChange = store::setIncludeCompleted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SwimDimens.Gap, Alignment.End),
            ) {
                if (clearVisible(filters)) SwimButton("Clear", store::clearFilters)
                SwimButton("Cancel", onDismiss)
                SwimButton("Apply", { onApply(); onDismiss() }, primary = true)
            }
        }
    }
}

@Composable
private fun FilterRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SwimDimens.Gap),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

private val FIELD = 190.dp
internal val FILTERS_DIALOG_WIDTH = 424.dp
