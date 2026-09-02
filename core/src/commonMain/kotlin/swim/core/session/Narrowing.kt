package swim.core.session

import swim.core.model.FilterOptions
import swim.core.model.LabelSummary
import swim.core.model.ProjectSummary
import swim.core.model.TeamSummary

/** The option lists the filter bar can offer, once the other selections have narrowed them. */
data class Availables(
    val teams: List<TeamSummary> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val labels: List<LabelSummary> = emptyList(),
)

/** Splits the comma-joined team filter into keys. */
fun teamKeys(team: String?): List<String> =
    team?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** Teams the selected project can reach. Every team, when no project is selected. */
fun availableTeams(all: List<TeamSummary>, selectedProject: ProjectSummary?): List<TeamSummary> =
    if (selectedProject == null) all else all.filter { it.key in selectedProject.teams }

/** Open projects sharing at least one team with the selection. Every project, when none is selected. */
fun availableProjects(allOpen: List<ProjectSummary>, selectedTeamKeys: List<String>): List<ProjectSummary> =
    if (selectedTeamKeys.isEmpty()) {
        allOpen
    } else {
        allOpen.filter { project -> project.teams.any { it in selectedTeamKeys } }
    }

/**
 * Workspace labels plus the selected teams' labels, one per lowercased name, sorted by name.
 * The filter matches labels by name, so two teams' labels sharing a name are one option.
 */
fun availableLabels(all: List<LabelSummary>, selectedTeamKeys: List<String>): List<LabelSummary> {
    val byName = LinkedHashMap<String, LabelSummary>()
    for (label in all) {
        if (selectedTeamKeys.isNotEmpty() && label.team != null && label.team !in selectedTeamKeys) continue
        byName.getOrPut(label.name.lowercase()) { label }
    }
    return byName.values.sortedBy { it.name.lowercase() }
}

/**
 * Drops the selections the other filters made impossible. [keepProject] holds the project a
 * pasted URL resolved to: that list covers completed and canceled projects, which the filter
 * bar's list does not, so reconciling against the bar would throw the URL's project away.
 */
fun reconcile(
    filters: FilterOptions,
    availables: Availables,
    keepProject: Boolean = false,
): FilterOptions {
    val selected = teamKeys(filters.team)
    val kept = selected.filter { key -> availables.teams.any { it.key == key } }
    val team = when {
        kept.size == selected.size -> filters.team
        kept.isEmpty() -> null
        else -> kept.joinToString(",")
    }
    val project = filters.project
        ?.takeIf { name -> keepProject || availables.projects.any { it.name == name } }
    val label = filters.label?.takeIf { name -> availables.labels.any { it.name == name } }
    val excludeLabel = filters.excludeLabel?.takeIf { name -> availables.labels.any { it.name == name } }

    return filters.copy(
        team = team,
        project = project,
        projectId = if (project == null) null else filters.projectId,
        label = label,
        excludeLabel = excludeLabel,
    )
}
