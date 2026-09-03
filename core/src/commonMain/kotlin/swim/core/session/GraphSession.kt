@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package swim.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import swim.core.analysis.findReadySet
import swim.core.analysis.hideDuplicates
import swim.core.github.GithubClient
import swim.core.linear.LinearClient
import swim.core.model.ApiError
import swim.core.model.FilterOptions
import swim.core.model.GraphData
import swim.core.model.IssueEdge
import swim.core.model.PrStatus
import swim.core.model.RelationType
import swim.core.model.SwimError
import swim.layout.Position
import swim.layout.PositionSnapshot
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What the graph is doing. */
sealed interface GraphState {
    /** Nothing has been asked for yet. Every launch starts here. */
    data object NotLoaded : GraphState

    /** A request is in flight. */
    data object Loading : GraphState

    /**
     * The last request answered. [filters] are the ones that produced [data], which is not the
     * live filter state: editing a filter arms a load without performing one. [data] already
     * carries the relations the pull requests imply, so the placement pass sees the full graph
     * the first time it runs.
     */
    data class Loaded(
        val data: GraphData,
        val filters: FilterOptions,
        val prStatuses: Map<String, PrStatus> = emptyMap(),
    ) : GraphState

    /** The last request failed. */
    data class Error(val error: SwimError) : GraphState
}

/** One relation an edge can be turned into. */
data class RelationChange(
    val label: String,
    val from: String,
    val to: String,
    val type: RelationType,
)

/**
 * Every relation [edge] could become, minus the one it already is. `blocks` is directional, so
 * it appears two times: as it is, and with the endpoints reversed as `blocked by`.
 */
fun changeOptions(edge: IssueEdge): List<RelationChange> = listOf(
    RelationChange("blocks", edge.from, edge.to, RelationType.BLOCKS),
    RelationChange("blocked by", edge.to, edge.from, RelationType.BLOCKS),
    RelationChange("related", edge.from, edge.to, RelationType.RELATED),
    RelationChange("duplicate", edge.from, edge.to, RelationType.DUPLICATE),
).filterNot { it.type == edge.type && it.from == edge.from }

/**
 * The graph the surfaces draw. Every flow is cold: a caller that never collects, such as the
 * CLI, pays nothing and still gets the one-shot mutations. Editing a filter only arms a load;
 * [FilterStore.applyFilters], [FilterStore.applyFromUrl] and [reload] are what load.
 */
class GraphSession(
    private val client: LinearClient,
    private val github: GithubClient?,
    private val filterStore: FilterStore,
    private val positions: PositionStore,
    private val scope: CoroutineScope,
) {
    private val reloads = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val prRefreshes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _showRelatedEdges = MutableStateFlow(true)
    private val _showDuplicates = MutableStateFlow(false)
    private val _derivePrRelations = MutableStateFlow(true)

    private var lastPrFetch: Pair<List<String>, Instant>? = null
    private var lastPrStatuses: Map<String, PrStatus> = emptyMap()

    private val _prStatusFailed = MutableStateFlow(false)

    // filterStore.loads is a SharedFlow, not the conflated state: an apply that a later state
    // write overtakes must still load.
    private val triggers: Flow<FilterOptions> = merge(
        filterStore.loads.distinctUntilChanged(),
        reloads.map { filterStore.filters },
    )

    /**
     * The graph for the filters last applied. A new trigger cancels the request in flight, so a
     * mutation that invalidates during a load cannot be overtaken by the answer it invalidated.
     * Lazy: a caller that never reads it starts no coroutine.
     */
    val graph: StateFlow<GraphState> by lazy {
        triggers
            .transformLatest { filters -> loadAndFollow(filters) }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GraphState.NotLoaded)
    }

    /** The issues nothing active blocks. A derived block counts only while the toggle is on. */
    val readySet: StateFlow<Set<String>> by lazy {
        combine(graph, _derivePrRelations) { state, derive ->
            (state as? GraphState.Loaded)?.data
                ?.let { if (derive) it else withoutPrRelations(it) }
                ?.let(::findReadySet)
                .orEmpty()
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())
    }

    /** Whether `related` edges are drawn. */
    val showRelatedEdges: StateFlow<Boolean> = _showRelatedEdges.asStateFlow()

    /** Whether issues on the duplicate side of a duplicate relation are drawn. */
    val showDuplicates: StateFlow<Boolean> = _showDuplicates.asStateFlow()

    /** Whether the relations the pull-request stacks imply are drawn. On unless the user says no. */
    val derivePrRelations: StateFlow<Boolean> = _derivePrRelations.asStateFlow()

    /**
     * The loaded graph with the view toggles applied, plus the stacks the surface draws as one
     * pile of cards. Empty until the first load answers.
     */
    val projected: StateFlow<GraphData> by lazy {
        combine(graph, _showRelatedEdges, _showDuplicates, _derivePrRelations) { state, related, duplicates, derive ->
            project((state as? GraphState.Loaded)?.data ?: EMPTY_GRAPH, related, duplicates, derive)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY_GRAPH)
    }

    /**
     * Whether the last pull-request status request failed. The badges then keep whatever they
     * had, so the surface must say the status is unavailable rather than show nothing.
     */
    val prStatusFailed: StateFlow<Boolean> = _prStatusFailed.asStateFlow()

    /**
     * Review and check status for every pull request the graph links to, in one batch per load.
     * Empty without a GitHub token. The load carries them, so the badges and the derived edges
     * always show the same answer. A load that is in flight keeps the last answer on screen.
     */
    val prStatuses: StateFlow<Map<String, PrStatus>> by lazy {
        graph
            .mapNotNull { (it as? GraphState.Loaded)?.prStatuses }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())
    }

    /** Loads the graph again with the current filters, even when nothing changed. */
    fun reload() {
        reloads.tryEmit(Unit)
    }

    /**
     * Asks GitHub about the loaded graph's pull requests again, without asking Linear. The graph
     * only emits again if the answer differs. It costs nothing while no graph is loaded.
     */
    fun refreshPrStatuses() {
        prRefreshes.tryEmit(Unit)
    }

    /** Draws or hides `related` edges. Duplicates are not affected. */
    fun setShowRelatedEdges(show: Boolean) {
        _showRelatedEdges.value = show
    }

    /** Draws or hides the issues that duplicate another issue. */
    fun setShowDuplicates(show: Boolean) {
        _showDuplicates.value = show
    }

    /** Draws or hides the relations the pull-request stacks imply. No load is necessary. */
    fun setDerivePrRelations(derive: Boolean) {
        _derivePrRelations.value = derive
        // Turning it on draws relations from an answer that may be a minute old, so ask again.
        if (derive) refreshPrStatuses()
    }

    /** Creates `from <type> to` and reloads. Returns the new relation id. */
    suspend fun createRelation(from: String, to: String, type: RelationType): String =
        client.createIssueRelation(from, to, type).also { reload() }

    /** Creates "[from] is blocked by [to]", which is the `blocks` relation with the ends swapped. */
    suspend fun createBlockedBy(from: String, to: String): String =
        createRelation(to, from, RelationType.BLOCKS)

    /** Turns [edge] into the same pair with a different type. Linear has no update, so this is delete then create. */
    suspend fun changeRelation(edge: IssueEdge, newType: RelationType): String =
        changeRelation(edge, RelationChange(newType.name.lowercase(), edge.from, edge.to, newType))

    /** Applies one option from [changeOptions]. Delete then create, because Linear has no update. */
    suspend fun changeRelation(edge: IssueEdge, change: RelationChange): String {
        client.deleteIssueRelation(relationIdOf(edge))
        return client.createIssueRelation(change.from, change.to, change.type).also { reload() }
    }

    /** Deletes [edge] and reloads. */
    suspend fun removeRelation(edge: IssueEdge) {
        client.deleteIssueRelation(relationIdOf(edge))
        reload()
    }

    /** Assigns an issue, or unassigns it when [userId] is null, then reloads. */
    suspend fun setAssignee(identifier: String, userId: String?) {
        client.setAssignee(identifier, userId)
        reload()
    }

    /** Moves an issue to a different workflow state, then reloads. */
    suspend fun setState(identifier: String, stateId: String) {
        client.setState(identifier, stateId)
        reload()
    }

    /** Changes an issue's priority, then reloads. */
    suspend fun setPriority(identifier: String, priority: Int) {
        client.setPriority(identifier, priority)
        reload()
    }

    /** Sets an issue's estimate, or clears it when [estimate] is null, then reloads. */
    suspend fun setEstimate(identifier: String, estimate: Int?) {
        client.setEstimate(identifier, estimate)
        reload()
    }

    /** Removes an issue from its project, then reloads. */
    suspend fun removeFromProject(identifier: String) {
        client.removeFromProject(identifier)
        reload()
    }

    /** Attaches a pull request (or any URL) to an issue, then reloads. */
    suspend fun attachPr(identifier: String, url: String) {
        client.attachPrUrl(identifier, url)
        reload()
    }

    /**
     * The key the visible query's hand-placed positions are saved under. The filters come from
     * the loaded graph, not from the live filter bar, which runs ahead of it as soon as the user
     * edits a filter. Grouping stays live, because grouping re-draws without a load.
     */
    fun layoutCacheKey(): String {
        val loaded = (graph.value as? GraphState.Loaded)?.filters ?: filterStore.filters
        return cacheKey(loaded, filterStore.state.value.groupBy)
    }

    /** Merges dragged node positions into the current query's saved layout. */
    fun savePositions(moved: Map<String, Position>) {
        if (moved.isEmpty()) return
        val key = layoutCacheKey()
        val snapshot = positions.get()
        val merged = snapshot.byKey[key].orEmpty() + moved
        positions.set(PositionSnapshot(snapshot.byKey + (key to merged)))
    }

    /**
     * One Linear load, then the pull requests for as long as this graph is the current one.
     *
     * Pull requests move while Linear stands still: one merges, one is retargeted, two issues
     * fold onto one branch. So the block stays alive after the load and asks GitHub again on
     * every [refreshPrStatuses]. `transformLatest` cancels the whole block on the next trigger,
     * and the sharing coroutine ends it when the last collector goes, so the follow-up can never
     * outlive the graph it belongs to.
     */
    private suspend fun FlowCollector<GraphState>.loadAndFollow(filters: FilterOptions) {
        emit(GraphState.Loading)
        val data = try {
            client.getIssuesWithRelations(filters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SwimError) {
            emit(GraphState.Error(e))
            return
        } catch (e: Exception) {
            // Anything unexpected would escape stateIn, and WhileSubscribed never restarts a
            // failed sharing coroutine: the graph would freeze for good.
            emit(GraphState.Error(ApiError("Linear could not answer: ${e.message}")))
            return
        }

        // The pull-request answer must arrive before Loaded. The placement pass runs on the first
        // graph it gets, and an edge that lands after it moves cards the user is already reading.
        var statuses = fetchPrStatuses(data)
        emit(GraphState.Loaded(withPrRelations(data, statuses), filters, statuses))

        if (github == null || prUrlsOf(data).isEmpty()) return
        prRefreshes.collect {
            val fresh = fetchPrStatuses(data, force = true)
            // The derived edges and the stacks are a pure function of the graph and the statuses,
            // so the same answer means the same value. Emitting it would re-run the placement
            // pass over cards that have not moved.
            if (fresh == statuses) return@collect
            statuses = fresh
            emit(GraphState.Loaded(withPrRelations(data, fresh), filters, fresh))
        }
    }

    /**
     * The statuses for one loaded graph. Never throws and never waits without an end: GitHub is an
     * overlay, so every failure degrades to the last good answer, and the graph loads regardless.
     * [force] is for a refresh that must ask, such as the one a new GitHub token earns.
     */
    private suspend fun fetchPrStatuses(data: GraphData, force: Boolean = false): Map<String, PrStatus> {
        if (github == null) return emptyMap()
        val urls = prUrlsOf(data)
        if (urls.isEmpty()) return emptyMap()

        val previous = lastPrFetch
        val now = Clock.System.now()
        if (!force && previous != null && previous.first == urls && now - previous.second < PR_STATUS_TTL) {
            return lastPrStatuses
        }

        // getPrStatuses answers null for every failure, and the HTTP engine ends a request that
        // gets no answer. The load therefore waits for GitHub, but never without an end.
        val result = github.getPrStatuses(urls)
        _prStatusFailed.value = result == null
        if (result == null) return lastPrStatuses
        // Stamped after the answer, not before: transformLatest cancels this block on the next
        // load, and a cancelled fetch that counted as done would blank every badge for a minute.
        lastPrFetch = urls to Clock.System.now()
        lastPrStatuses = result
        return result
    }

    private fun relationIdOf(edge: IssueEdge): String = edge.relationId
        ?: throw IllegalArgumentException("The ${edge.type} edge ${edge.from} -> ${edge.to} has no relation id.")
}

/**
 * Applies the view toggles: duplicates are removed with their edges, `related` edges only hidden,
 * and the pull-request relations either kept or dropped. The derived edges stay at the end of the
 * list, after the Linear ones, so a cycle-breaking pass drops the derived edge first.
 */
internal fun project(
    data: GraphData,
    showRelatedEdges: Boolean,
    showDuplicates: Boolean,
    derivePrRelations: Boolean,
): GraphData {
    val derived = if (derivePrRelations) data else withoutPrRelations(data)
    val visible = if (showDuplicates) derived else hideDuplicates(derived)
    return if (showRelatedEdges) {
        visible
    } else {
        visible.copy(edges = visible.edges.filterNot { it.type == RelationType.RELATED })
    }
}

/** Every pull request the graph links to, in the order the batched GitHub query wants them. */
private fun prUrlsOf(data: GraphData): List<String> =
    data.nodes.flatMap { it.pullRequests.orEmpty() }.map { it.url }.distinct().sorted()

private val EMPTY_GRAPH = GraphData(nodes = emptyList(), edges = emptyList())

/**
 * How long one pull-request answer is good for. It suppresses a second ask inside the window, and
 * it is the cadence a surface must re-ask on. One interval, one place to change it.
 */
val PR_STATUS_TTL: Duration = 60.seconds

// Long enough that a configuration change does not drop the graph and reload it.
private const val STOP_TIMEOUT_MS = 5_000L
