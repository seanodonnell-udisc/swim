@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package swim.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import swim.core.analysis.findReadySet
import swim.core.analysis.hideDuplicates
import swim.core.github.GithubClient
import swim.core.linear.LinearClient
import swim.core.model.FilterOptions
import swim.core.model.GraphData
import swim.core.model.IssueEdge
import swim.core.model.PrStatus
import swim.core.model.RelationType
import swim.core.model.SwimError
import swim.layout.Position
import swim.layout.PositionSnapshot
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What the graph is doing. */
sealed interface GraphState {
    /** Nothing has been asked for yet. Every launch starts here. */
    data object NotLoaded : GraphState

    /** A request is in flight. */
    data object Loading : GraphState

    /** The last request answered. */
    data class Loaded(val data: GraphData) : GraphState

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

    private val _showRelatedEdges = MutableStateFlow(true)
    private val _showDuplicates = MutableStateFlow(false)

    private var lastPrFetch: Pair<List<String>, Instant>? = null

    private val triggers: Flow<FilterOptions> = merge(
        filterStore.state.filter { it.shouldLoadIssues }.map { it.filters }.distinctUntilChanged(),
        reloads.map { filterStore.filters },
    )

    /**
     * The graph for the filters last applied. A new trigger cancels the request in flight, so a
     * mutation that invalidates during a load cannot be overtaken by the answer it invalidated.
     * Lazy: a caller that never reads it starts no coroutine.
     */
    val graph: StateFlow<GraphState> by lazy {
        triggers
            .transformLatest { filters ->
                emit(GraphState.Loading)
                emit(
                    try {
                        GraphState.Loaded(client.getIssuesWithRelations(filters))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SwimError) {
                        GraphState.Error(e)
                    }
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GraphState.NotLoaded)
    }

    /** The issues nothing active blocks. */
    val readySet: StateFlow<Set<String>> by lazy {
        graph
            .map { state -> (state as? GraphState.Loaded)?.let { findReadySet(it.data) }.orEmpty() }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())
    }

    /** Whether `related` edges are drawn. */
    val showRelatedEdges: StateFlow<Boolean> = _showRelatedEdges.asStateFlow()

    /** Whether issues on the duplicate side of a duplicate relation are drawn. */
    val showDuplicates: StateFlow<Boolean> = _showDuplicates.asStateFlow()

    /** The loaded graph with the view toggles applied. Empty until the first load answers. */
    val projected: StateFlow<GraphData> by lazy {
        combine(graph, _showRelatedEdges, _showDuplicates) { state, related, duplicates ->
            project((state as? GraphState.Loaded)?.data ?: EMPTY_GRAPH, related, duplicates)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY_GRAPH)
    }

    /**
     * Review and check status for every pull request the graph links to, in one batch per load.
     * Empty without a GitHub token. The same set of URLs is not asked for again inside a minute.
     */
    val prStatuses: StateFlow<Map<String, PrStatus>> by lazy {
        graph
            .mapNotNull { (it as? GraphState.Loaded)?.data }
            .map { data -> data.nodes.flatMap { it.pullRequests.orEmpty() }.map { it.url }.distinct().sorted() }
            .transformLatest { urls -> fetchPrStatuses(urls)?.let { emit(it) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())
    }

    /** Loads the graph again with the current filters, even when nothing changed. */
    fun reload() {
        reloads.tryEmit(Unit)
    }

    /** Draws or hides `related` edges. Duplicates are not affected. */
    fun setShowRelatedEdges(show: Boolean) {
        _showRelatedEdges.value = show
    }

    /** Draws or hides the issues that duplicate another issue. */
    fun setShowDuplicates(show: Boolean) {
        _showDuplicates.value = show
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

    /** The key the current query's hand-placed positions are saved under. */
    fun layoutCacheKey(): String = filterStore.state.value.let { cacheKey(it.filters, it.groupBy) }

    /** Merges dragged node positions into the current query's saved layout. */
    fun savePositions(moved: Map<String, Position>) {
        if (moved.isEmpty()) return
        val key = layoutCacheKey()
        val snapshot = positions.get()
        val merged = snapshot.byKey[key].orEmpty() + moved
        positions.set(PositionSnapshot(snapshot.byKey + (key to merged)))
    }

    private suspend fun fetchPrStatuses(urls: List<String>): Map<String, PrStatus>? {
        if (github == null || urls.isEmpty()) return emptyMap()
        val previous = lastPrFetch
        val now = Clock.System.now()
        if (previous != null && previous.first == urls && now - previous.second < PR_STATUS_TTL) return null
        lastPrFetch = urls to now
        return github.getPrStatuses(urls)
    }

    private fun relationIdOf(edge: IssueEdge): String = edge.relationId
        ?: throw IllegalArgumentException("The ${edge.type} edge ${edge.from} -> ${edge.to} has no relation id.")
}

/** Applies the two view toggles: duplicates are removed with their edges, `related` edges only hidden. */
internal fun project(data: GraphData, showRelatedEdges: Boolean, showDuplicates: Boolean): GraphData {
    val visible = if (showDuplicates) data else hideDuplicates(data)
    return if (showRelatedEdges) {
        visible
    } else {
        visible.copy(edges = visible.edges.filterNot { it.type == RelationType.RELATED })
    }
}

private val EMPTY_GRAPH = GraphData(nodes = emptyList(), edges = emptyList())

private val PR_STATUS_TTL = 60.seconds

// Long enough that a configuration change does not drop the graph and reload it.
private const val STOP_TIMEOUT_MS = 5_000L
