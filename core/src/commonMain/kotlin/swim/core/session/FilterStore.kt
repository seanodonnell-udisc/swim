package swim.core.session

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import swim.core.model.FilterOptions
import swim.core.model.ResolvedLinearUrl

/** How the graph groups its nodes. Grouping is a view setting, not part of the query. */
@Serializable
enum class GraphGrouping { NONE, TEAM, PROJECT, LABEL }

/** The filter bar as a whole: what to ask Linear for, and whether to ask yet. */
data class FilterState(
    val filters: FilterOptions = FilterOptions(),
    val groupBy: GraphGrouping = GraphGrouping.NONE,
    val shouldLoadIssues: Boolean = false,
    val urlSource: String? = null,
)

/** The settings key the filter bar persists under. */
const val FILTERS_KEY: String = "swim.filters"

/**
 * The filter bar's state machine. Editing a filter ARMS a load: it clears `shouldLoadIssues` and
 * forgets the URL the filters came from. Only [applyFilters] and [applyFromUrl] load. Filters and
 * grouping survive a restart; the load flag and the URL source never do, so every launch starts
 * un-loaded.
 */
class FilterStore(
    private val settings: Settings,
    private val key: String = FILTERS_KEY,
) {
    private val _state = MutableStateFlow(restore())

    /** The current filter bar state. */
    val state: StateFlow<FilterState> = _state.asStateFlow()

    /** The filters as the client wants them. */
    val filters: FilterOptions get() = _state.value.filters

    /** Sets the comma-joined team keys. */
    fun setTeam(team: String?) = arm { it.copy(team = team) }

    /** Sets the project by name, and by id when the caller knows it. */
    fun setProject(project: String?, projectId: String? = null) =
        arm { it.copy(project = project, projectId = if (project == null) null else projectId) }

    /** Sets the label an issue must carry. */
    fun setLabel(label: String?) = arm { it.copy(label = label) }

    /** Sets the label an issue must not carry. */
    fun setExcludeLabel(excludeLabel: String?) = arm { it.copy(excludeLabel = excludeLabel) }

    /** Sets the priority, 0 through 4. */
    fun setPriority(priority: Int?) = arm { it.copy(priority = priority) }

    /** Sets the state-name substring. */
    fun setState(state: String?) = arm { it.copy(state = state) }

    /** Sets the comma-joined state types. */
    fun setStateType(stateType: String?) = arm { it.copy(stateType = stateType) }

    /** Sets the assignee-name substring. */
    fun setAssignee(assignee: String?) = arm { it.copy(assignee = assignee) }

    /** Includes completed and canceled issues. */
    fun setIncludeCompleted(includeCompleted: Boolean) = arm { it.copy(includeCompleted = includeCompleted) }

    /** Sets the cycle. */
    fun setCycleId(cycleId: String?) = arm { it.copy(cycleId = cycleId) }

    /** Replaces every filter at once. Used by the reconciliation pass. */
    fun setFilters(filters: FilterOptions) = arm { filters }

    /** Changes the grouping. Grouping re-draws the same data, so it does not arm a load. */
    fun setGroupBy(groupBy: GraphGrouping) = publish(_state.value.copy(groupBy = groupBy))

    /** Loads the issues the current filters name. */
    fun applyFilters() = publish(_state.value.copy(shouldLoadIssues = true))

    /** Clears every filter. Grouping and the load flag reset with it. */
    fun clearFilters() = publish(FilterState(groupBy = _state.value.groupBy))

    /** Replaces every filter with the ones a pasted Linear URL resolved to, and loads at once. */
    fun applyFromUrl(resolved: ResolvedLinearUrl) = publish(
        FilterState(
            filters = resolved.filters,
            groupBy = _state.value.groupBy,
            shouldLoadIssues = true,
            urlSource = resolved.urlSource,
        )
    )

    /** Hides the "from this URL" chip without touching the filters it produced. */
    fun dismissUrlSource() = publish(_state.value.copy(urlSource = null))

    private fun arm(edit: (FilterOptions) -> FilterOptions) = publish(
        _state.value.let {
            it.copy(filters = edit(it.filters), shouldLoadIssues = false, urlSource = null)
        }
    )

    private fun publish(next: FilterState) {
        _state.value = next
        settings.putString(key, filterJson.encodeToString(Persisted.serializer(), Persisted(next.filters, next.groupBy)))
    }

    private fun restore(): FilterState {
        val stored = settings.getStringOrNull(key) ?: return FilterState()
        val persisted = try {
            filterJson.decodeFromString(Persisted.serializer(), stored)
        } catch (e: Exception) {
            return FilterState()
        }
        return FilterState(filters = persisted.filters, groupBy = persisted.groupBy)
    }
}

@Serializable
private data class Persisted(val filters: FilterOptions, val groupBy: GraphGrouping)

private val filterJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
