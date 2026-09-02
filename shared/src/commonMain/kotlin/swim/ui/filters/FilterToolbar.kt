package swim.ui.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import swim.core.model.FilterOptions
import swim.core.model.LabelSummary
import swim.core.model.PRIORITY_LABELS
import swim.core.model.ProjectSummary
import swim.core.model.TeamSummary
import swim.core.model.UserSummary
import swim.core.session.Availables
import swim.core.session.FilterState
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
import swim.ui.theme.SwimDimens

/** The workspace lists the filter bar picks from. Empty until the first reference load answers. */
data class ReferenceData(
    val teams: List<TeamSummary> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val labels: List<LabelSummary> = emptyList(),
    val users: List<UserSummary> = emptyList(),
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
 * Row one of the toolbar: the query. Every control writes through [store], so editing one arms a
 * load instead of running it.
 */
@Composable
internal fun FilterToolbar(
    state: FilterState,
    availables: Availables,
    store: FilterStore,
    loaded: Boolean,
    loading: Boolean,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val filters = state.filters
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = SwimDimens.HeaderHeight)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SwimDimens.Gap),
    ) {
        // The query controls scroll when the window is narrow; the actions stay pinned right.
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SwimDimens.Gap),
        ) {
            SwimMultiSelect(
                label = "Team",
                selected = teamKeys(filters.team),
                options = availables.teams.map { SwimOption(it.key, "${it.key} · ${it.name}") },
                onChange = { store.setTeam(commaJoin(it)) },
                width = 112.dp,
            )
            SwimSelect(
                label = "Project",
                selected = filters.project,
                options = availables.projects.map { SwimOption(it.name, it.name) },
                onSelect = { name ->
                    store.setProject(name, availables.projects.firstOrNull { it.name == name }?.id)
                },
                width = 160.dp,
            )
            SwimSelect(
                label = "Label",
                selected = filters.label,
                options = availables.labels.map { SwimOption(it.name, it.name) },
                onSelect = store::setLabel,
                width = 112.dp,
            )
            SwimSelect(
                label = "Exclude",
                selected = filters.excludeLabel,
                options = availables.labels.map { SwimOption(it.name, it.name) },
                onSelect = store::setExcludeLabel,
                width = 118.dp,
            )
            SwimSelect(
                label = "Priority",
                selected = filters.priority?.toString(),
                options = PRIORITY_OPTIONS,
                onSelect = { store.setPriority(it?.toIntOrNull()) },
                width = 124.dp,
            )
            SwimMultiSelect(
                label = "Status",
                selected = commaValues(filters.stateType),
                options = STATE_TYPE_OPTIONS,
                onChange = { store.setStateType(commaJoin(it)) },
                width = 118.dp,
            )
            SwimTextField(
                value = filters.state.orEmpty(),
                onValueChange = { store.setState(it.ifBlank { null }) },
                placeholder = "State",
                width = 88.dp,
            )
            SwimTextField(
                value = filters.assignee.orEmpty(),
                onValueChange = { store.setAssignee(it.ifBlank { null }) },
                placeholder = "Assignee",
                width = 92.dp,
            )
            SwimCheckbox(
                label = "Completed",
                checked = filters.includeCompleted,
                onCheckedChange = store::setIncludeCompleted,
            )
        }

        SwimButton(
            text = loadButtonLabel(loaded, !state.shouldLoadIssues),
            primary = true,
            enabled = !loading,
            onClick = onLoad,
        )
        if (clearVisible(filters)) {
            SwimButton("Clear", store::clearFilters)
        }
        trailing()
    }
}
