package swim.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import swim.core.auth.LinearAuthMode
import swim.core.model.IssueEdge
import swim.core.model.RelationType
import swim.core.model.SwimError
import swim.core.session.AuthStatus
import swim.core.session.GraphGrouping
import swim.core.session.GraphState
import swim.core.session.RelationChange
import swim.core.session.reconcile
import swim.core.url.resolveLinearUrl
import swim.layout.PositionSnapshot
import swim.ui.auth.GithubCard
import swim.ui.filters.FilterToolbar
import swim.ui.filters.LinearUrlInput
import swim.ui.filters.ReferenceData
import swim.ui.filters.availablesOf
import swim.ui.graph.EdgeKey
import swim.ui.graph.GraphCanvas
import swim.ui.graph.GraphCanvasCallbacks
import swim.ui.graph.Swim
import swim.ui.graph.rememberGraphCanvasState
import swim.ui.theme.SwimDimens

/** One mutation waiting for the user to confirm it. */
private class Pending(
    val detail: String,
    val confirmLabel: String,
    val run: suspend () -> Unit,
)

/** The graph, its filter bar, and the confirm step in front of every Linear mutation. */
@Composable
internal fun GraphScreen(
    env: SwimEnv,
    holder: SwimSession,
    status: AuthStatus,
    onAuthChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val filterState by holder.filters.state.collectAsState()
    val graphState by holder.session.graph.collectAsState()
    val projected by holder.session.projected.collectAsState()
    val readySet by holder.session.readySet.collectAsState()
    val prStatuses by holder.session.prStatuses.collectAsState()
    val showRelated by holder.session.showRelatedEdges.collectAsState()
    val showDuplicates by holder.session.showDuplicates.collectAsState()

    var reference by remember { mutableStateOf(ReferenceData()) }
    var placement by remember { mutableStateOf(GraphPlacement()) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    var urlResolving by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var githubDialog by remember { mutableStateOf(false) }
    var relayouts by remember { mutableStateOf(0) }
    val canvasState = rememberGraphCanvasState()

    val availables = remember(reference, filterState.filters) {
        availablesOf(reference, filterState.filters)
    }

    LaunchedEffect(holder) {
        try {
            reference = ReferenceData(
                teams = holder.client.getTeams(),
                projects = holder.client.getProjectSummaries(),
                labels = holder.client.getLabelSummaries(),
                users = holder.client.getUsers(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SwimError) {
            banner = e.message
        }
    }

    // Drop the selections the other filters made impossible, once the lists have arrived.
    LaunchedEffect(availables, filterState.filters) {
        if (reference.teams.isEmpty()) return@LaunchedEffect
        val reconciled = reconcile(filterState.filters, availables)
        if (reconciled != filterState.filters) holder.filters.setFilters(reconciled)
    }

    LaunchedEffect(env) {
        env.devAutoload?.let { spec ->
            parseAutoload(spec)?.let {
                holder.filters.setFilters(it)
                holder.filters.applyFilters()
            }
        }
    }

    LaunchedEffect(graphState) {
        env.log(
            when (val state = graphState) {
                is GraphState.Loaded ->
                    "graph loaded: ${state.data.nodes.size} issues, ${state.data.edges.size} relations"
                is GraphState.Error -> "graph failed: ${state.error.message}"
                GraphState.Loading -> "graph loading"
                GraphState.NotLoaded -> "graph not loaded"
            }
        )
    }

    LaunchedEffect(projected, filterState.groupBy, relayouts) {
        val key = holder.session.layoutCacheKey()
        val snapshot = holder.positions.get()
        val next = withContext(Dispatchers.Default) {
            placeGraph(projected, filterState.groupBy, key, snapshot)
        }
        if (next.snapshot != snapshot) holder.positions.set(next.snapshot)
        placement = next
    }

    LaunchedEffect(env.commands) {
        env.commands.commands.collect { command ->
            when (command) {
                AppCommand.RELOAD -> holder.session.reload()
                AppCommand.RELAYOUT -> {
                    forgetLayout(holder)
                    relayouts++
                }
                AppCommand.ZOOM_IN -> canvasState.zoomIn()
                AppCommand.ZOOM_OUT -> canvasState.zoomOut()
                AppCommand.ZOOM_FIT -> canvasState.fitToContent()
            }
        }
    }

    fun edgeOf(key: EdgeKey): IssueEdge? = projected.edges.firstOrNull {
        it.from == key.from && it.to == key.to && it.type == key.type
    }

    fun mutate(detail: String, confirmLabel: String, run: suspend () -> Unit) {
        pending = Pending(detail, confirmLabel, run)
    }

    Column(Modifier.fillMaxSize().background(Swim.Bg)) {
        FilterToolbar(
            state = filterState,
            availables = availables,
            store = holder.filters,
            loaded = graphState is GraphState.Loaded,
            loading = graphState is GraphState.Loading,
            onLoad = {
                if (filterState.shouldLoadIssues) holder.session.reload() else holder.filters.applyFilters()
            },
        ) {
            OverflowMenu(
                githubConnected = status.githubConfigured,
                onRelayout = {
                    forgetLayout(holder)
                    relayouts++
                },
                onConnectGithub = { githubDialog = true },
                onSignOut = {
                    scope.launch {
                        signOut(env, holder)
                        onAuthChanged()
                    }
                },
            )
        }
        Divider()

        ViewToolbar(
            counts = "${projected.nodes.size} issues, ${projected.edges.size} relations",
            groupBy = filterState.groupBy,
            onGroupBy = holder.filters::setGroupBy,
            showRelated = showRelated,
            onShowRelated = holder.session::setShowRelatedEdges,
            showDuplicates = showDuplicates,
            onShowDuplicates = holder.session::setShowDuplicates,
            urlSource = filterState.urlSource,
            onDismissUrlSource = holder.filters::dismissUrlSource,
            urlResolving = urlResolving,
            urlError = urlError,
            onUrlSubmit = { url ->
                urlError = null
                urlResolving = true
                scope.launch {
                    try {
                        holder.filters.applyFromUrl(resolveLinearUrl(url, holder.client))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SwimError) {
                        urlError = e.message
                    } finally {
                        urlResolving = false
                    }
                }
            },
        )
        Divider()

        banner?.let { ErrorBanner(it, { banner = null }) }

        Box(Modifier.fillMaxSize()) {
            when {
                graphState is GraphState.NotLoaded -> CenteredNotice(
                    title = "No issues loaded",
                    detail = "Pick a team or a project, then press Load issues.",
                    modifier = Modifier.fillMaxSize(),
                )

                graphState is GraphState.Loading -> CenteredNotice(
                    title = "Loading issues…",
                    detail = null,
                    modifier = Modifier.fillMaxSize(),
                    accent = Swim.Accent,
                )

                graphState is GraphState.Error -> CenteredNotice(
                    title = "Linear could not answer",
                    detail = (graphState as GraphState.Error).error.message,
                    modifier = Modifier.fillMaxSize(),
                    accent = Swim.Red,
                    action = { SwimButton("Retry", { holder.session.reload() }, primary = true) },
                )

                projected.nodes.isEmpty() -> CenteredNotice(
                    title = "No issues match",
                    detail = "Widen the filters, or turn on Include completed.",
                    modifier = Modifier.fillMaxSize(),
                )

                else -> GraphCanvas(
                    graph = projected,
                    positions = placement.positions,
                    modifier = Modifier.fillMaxSize(),
                    readySet = readySet,
                    prStatuses = prStatuses,
                    users = reference.users,
                    crossLinks = placement.crossLinks,
                    cycleEdges = placement.cycleEdges,
                    selection = selection,
                    state = canvasState,
                    callbacks = GraphCanvasCallbacks(
                        onOpenIssue = { id ->
                            projected.nodes.firstOrNull { it.identifier == id }?.url?.let(env.openUrl)
                        },
                        onOpenUrl = env.openUrl,
                        onCopyId = env.copyToClipboard,
                        onAssign = { id, userId ->
                            val who = reference.users.firstOrNull { it.id == userId }?.name ?: "nobody"
                            mutate("This assigns $id to $who in Linear.", "Assign") {
                                holder.session.setAssignee(id, userId)
                            }
                        },
                        onCreateRelation = { from, to, type, reversed ->
                            mutate(relationDetail(from, to, type, reversed), "Create") {
                                if (reversed && type == RelationType.BLOCKS) {
                                    holder.session.createBlockedBy(from, to)
                                } else {
                                    holder.session.createRelation(from, to, type)
                                }
                            }
                        },
                        onChangeRelation = { key, type, reversed ->
                            val edge = edgeOf(key) ?: return@GraphCanvasCallbacks
                            val change = if (reversed && type == RelationType.BLOCKS) {
                                RelationChange("blocked by", edge.to, edge.from, type)
                            } else {
                                RelationChange(type.name.lowercase(), edge.from, edge.to, type)
                            }
                            mutate(
                                relationDetail(key.from, key.to, type, reversed),
                                "Change",
                            ) { holder.session.changeRelation(edge, change) }
                        },
                        onRemoveRelation = { key ->
                            val edge = edgeOf(key) ?: return@GraphCanvasCallbacks
                            mutate(
                                "This removes the ${key.type.name.lowercase()} relation " +
                                    "${key.from} → ${key.to} in Linear.",
                                "Remove",
                            ) { holder.session.removeRelation(edge) }
                        },
                        onNodesMoved = { moved ->
                            holder.session.savePositions(moved)
                            placement = placement.copy(positions = placement.positions + moved)
                                .let { it.copy(groups = groupBoxesOf(projected, filterState.groupBy, it.positions)) }
                        },
                        onSelectionChange = { selection = it },
                    ),
                    underlay = { GroupUnderlay(placement.groups) },
                )
            }
        }
    }

    pending?.let { request ->
        ConfirmDialog(
            title = "Confirm change",
            detail = request.detail,
            confirmLabel = request.confirmLabel,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                scope.launch {
                    try {
                        request.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SwimError) {
                        banner = e.message
                        env.log("mutation failed: ${e.message}")
                    } catch (e: IllegalArgumentException) {
                        banner = e.message
                        env.log("mutation refused: ${e.message}")
                    }
                }
            },
        )
    }

    if (githubDialog) {
        Box(
            modifier = Modifier.fillMaxSize().background(Swim.Bg.copy(alpha = 0.85f))
                .clickable { githubDialog = false },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(460.dp)) {
                GithubCard(env, scope) {
                    githubDialog = false
                    onAuthChanged()
                }
            }
        }
    }
}

/** Row two: how the graph is drawn, and where its filters came from. */
@Composable
private fun ViewToolbar(
    counts: String,
    groupBy: GraphGrouping,
    onGroupBy: (GraphGrouping) -> Unit,
    showRelated: Boolean,
    onShowRelated: (Boolean) -> Unit,
    showDuplicates: Boolean,
    onShowDuplicates: (Boolean) -> Unit,
    urlSource: String?,
    onDismissUrlSource: () -> Unit,
    urlResolving: Boolean,
    urlError: String?,
    onUrlSubmit: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(SwimDimens.HeaderHeight).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SwimDimens.Gap),
    ) {
        LinearUrlInput(resolving = urlResolving, error = urlError, onSubmit = onUrlSubmit)
        urlSource?.let { DismissibleChip("From: $it", onDismissUrlSource) }
        Spacer(Modifier.weight(1f))
        SwimSelect(
            label = "Group",
            selected = if (groupBy == GraphGrouping.NONE) null else groupBy.name.lowercase(),
            options = listOf(
                SwimOption("team", "Team"),
                SwimOption("project", "Project"),
                SwimOption("label", "Label"),
            ),
            onSelect = { value ->
                onGroupBy(
                    when (value) {
                        "team" -> GraphGrouping.TEAM
                        "project" -> GraphGrouping.PROJECT
                        "label" -> GraphGrouping.LABEL
                        else -> GraphGrouping.NONE
                    }
                )
            },
            width = 130.dp,
            anyLabel = "None",
        )
        SwimCheckbox("Related edges", showRelated, onShowRelated)
        SwimCheckbox("Duplicates", showDuplicates, onShowDuplicates)
        Text(counts, color = Swim.TextMuted, fontSize = 11.sp)
    }
}

/** The overflow menu: everything that is not a filter. */
@Composable
private fun OverflowMenu(
    githubConnected: Boolean,
    onRelayout: () -> Unit,
    onConnectGithub: () -> Unit,
    onSignOut: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SwimButton("⋯", { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Swim.Card),
        ) {
            MenuItem("Re-layout") {
                open = false
                onRelayout()
            }
            if (!githubConnected) {
                MenuItem("Connect GitHub…") {
                    open = false
                    onConnectGithub()
                }
            }
            MenuItem("Sign out") {
                open = false
                onSignOut()
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Swim.Text,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The labelled outline behind one group's members. This draws instead of laying out: the
 * outline is wider than the viewport, and a laid-out box would be measured against it.
 */
@Composable
private fun GroupUnderlay(groups: List<GroupBox>) {
    if (groups.isEmpty()) return
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        // Canvas units are dp; this draw scope is pixels.
        groups.forEach { group ->
            val topLeft = Offset(group.x * density, group.y * density)
            val size = Size(group.width * density, group.height * density)
            val radius = CornerRadius(10f * density)
            drawRoundRect(Swim.Card.copy(alpha = 0.5f), topLeft, size, radius)
            drawRoundRect(Swim.Border, topLeft, size, radius, style = Stroke(1f * density))
            drawText(
                textLayoutResult = measurer.measure(group.label, GROUP_LABEL_STYLE),
                topLeft = topLeft + Offset(14f * density, 9f * density),
            )
        }
    }
}

private val GROUP_LABEL_STYLE = TextStyle(
    color = Swim.TextMuted,
    fontSize = 15.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Swim.Border))
}

/** The wording of a relation confirm. A reversed blocks relation reads as "blocked by". */
private fun relationDetail(
    from: String,
    to: String,
    type: RelationType,
    reversed: Boolean,
): String {
    val verb = when {
        type == RelationType.BLOCKS && reversed -> "$from blocked by $to"
        type == RelationType.BLOCKS -> "$from blocks $to"
        type == RelationType.RELATED -> "$from related to $to"
        else -> "$from duplicate of $to"
    }
    return "This changes the relation in Linear: $verb."
}

/** Drops this query's saved layout so the next placement runs fresh. */
private fun forgetLayout(holder: SwimSession) {
    val snapshot = holder.positions.get()
    holder.positions.set(PositionSnapshot(snapshot.byKey - holder.session.layoutCacheKey()))
}

private suspend fun signOut(env: SwimEnv, holder: SwimSession) {
    val stored = env.tokenStore.getLinear()
    if (stored?.mode == LinearAuthMode.OAUTH) {
        try {
            holder.oauth.revoke(stored.accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A revoke that fails must not trap the user in a signed-in app.
        }
    }
    env.tokenStore.clearLinear()
    env.tokenStore.clearGithub()
}
