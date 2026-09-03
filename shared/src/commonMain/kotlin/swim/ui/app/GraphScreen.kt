package swim.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import swim.core.auth.LinearAuthMode
import swim.core.model.IssueEdge
import swim.core.model.PRIORITY_LABELS
import swim.core.model.RelationType
import swim.core.model.StateSummary
import swim.core.model.SwimError
import swim.core.session.AuthStatus
import swim.core.session.GraphGrouping
import swim.core.session.GraphState
import swim.core.session.PR_STATUS_TTL
import swim.core.session.RelationChange
import swim.core.session.reconcile
import swim.core.url.resolveLinearUrl
import swim.layout.LayoutEdge
import swim.layout.LayoutEdgeKind
import swim.layout.Position
import swim.layout.PositionSnapshot
import swim.layout.relayoutDescendants
import swim.ui.auth.GithubCard
import swim.ui.filters.FilterToolbar
import swim.ui.filters.LinearUrlInput
import swim.ui.filters.ReferenceData
import swim.ui.filters.availablesOf
import swim.ui.graph.CanvasMode
import swim.ui.graph.EdgeKey
import swim.ui.graph.GraphCanvas
import swim.ui.graph.GraphCanvasCallbacks
import swim.ui.graph.GraphCanvasDefaults
import swim.ui.graph.Swim
import swim.ui.graph.layoutEdgesOf
import swim.ui.graph.layoutNodesOf
import swim.ui.graph.rememberGraphCanvasState
import swim.ui.graph.slotOf
import swim.ui.graph.stackIndex
import swim.ui.graph.visibleStacks
import swim.ui.theme.SwimDimens

/** Set once the shortcuts overlay has been shown, so it only greets a new install. */
private const val SEEN_SHORTCUTS = "swim.seenShortcuts"

/** One mutation waiting for the user to confirm it. */
private class Pending(
    val detail: String,
    val confirmLabel: String,
    val run: suspend () -> Unit,
    /** Runs once the mutation has gone through, before the graph reloads into view. */
    val after: () -> Unit = {},
)

/** How long the tuck-under slide takes. Long enough to follow, short enough not to wait on. */
private const val TUCK_MS = 200f

/**
 * Slides every moved card from where it is to where it belongs, then hands over the final map.
 *
 * ponytail: driven off `withFrameMillis`, not an `Animatable`. `animation-core` is not a declared
 * dependency of `:shared`, and a straight lerp over 200ms is the whole requirement.
 */
private suspend fun slideTo(
    from: Map<String, Position>,
    to: Map<String, Position>,
    onFrame: (Map<String, Position>) -> Unit,
) {
    val moved = to.filter { (id, at) -> from[id] != at }
    if (moved.isEmpty()) {
        onFrame(to)
        return
    }
    val start = withFrameMillis { it }
    while (true) {
        val fraction = ((withFrameMillis { it } - start) / TUCK_MS).coerceAtMost(1f)
        onFrame(
            to + moved.mapValues { (id, end) ->
                val begin = from[id] ?: end
                Position(
                    begin.x + (end.x - begin.x) * fraction,
                    begin.y + (end.y - begin.y) * fraction,
                )
            },
        )
        if (fraction >= 1f) return
    }
}

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
    val prStatusFailed by holder.session.prStatusFailed.collectAsState()
    val showRelated by holder.session.showRelatedEdges.collectAsState()
    val showDuplicates by holder.session.showDuplicates.collectAsState()
    val derivePr by holder.session.derivePrRelations.collectAsState()

    var reference by remember { mutableStateOf(ReferenceData()) }
    var states by remember { mutableStateOf(emptyMap<String, List<StateSummary>>()) }
    var tuck by remember { mutableStateOf<Map<String, Position>?>(null) }
    var placement by remember { mutableStateOf(GraphPlacement()) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    var urlResolving by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var githubDialog by remember { mutableStateOf(false) }
    var relayouts by remember { mutableStateOf(0) }
    var showCrossLinks by remember { mutableStateOf(false) }
    var areaDrag by remember { mutableStateOf(Offset.Zero) }
    val canvasState = rememberGraphCanvasState()

    // Milestone areas hide their crossings until the view toolbar asks for them.
    val drawn = remember(projected, filterState.groupBy, showCrossLinks) {
        if (filterState.groupBy != GraphGrouping.MILESTONE || showCrossLinks) projected
        else withoutCrossGroupEdges(projected, filterState.groupBy)
    }

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

    // The Status submenu needs each team's own workflow states, so they are fetched per team the
    // graph actually shows. The client caches them, so a reload of the same teams costs nothing.
    LaunchedEffect(projected, reference.teams) {
        val wanted = projected.nodes.mapTo(mutableSetOf()) { it.team } - states.keys
        if (wanted.isEmpty() || reference.teams.isEmpty()) return@LaunchedEffect
        val fetched = wanted.mapNotNull { key ->
            val team = reference.teams.firstOrNull { it.key == key } ?: return@mapNotNull null
            runCatching { key to holder.client.getStates(team.id) }.getOrNull()
        }
        if (fetched.isNotEmpty()) states = states + fetched
    }

    // Drop the selections the other filters made impossible, once the lists have arrived.
    LaunchedEffect(availables, filterState.filters, filterState.urlSource) {
        if (reference.teams.isEmpty()) return@LaunchedEffect
        val reconciled = reconcile(
            filters = filterState.filters,
            availables = availables,
            keepProject = filterState.urlSource != null,
        )
        if (reconciled != filterState.filters) holder.filters.setFilters(reconciled)
    }

    // The gestures the canvas offers are not written anywhere else, so the first run says so.
    LaunchedEffect(Unit) {
        if (!env.settings.getBoolean(SEEN_SHORTCUTS, false)) {
            canvasState.shortcutsVisible = true
            env.settings.putBoolean(SEEN_SHORTCUTS, true)
        }
    }

    LaunchedEffect(env) {
        env.devAutoload?.let { spec ->
            parseAutoload(spec)?.let {
                holder.filters.setFilters(it)
                holder.filters.applyFilters()
            }
        }
    }

    // Pull requests move while Linear stands still: one merges, one is retargeted, two issues
    // fold onto one branch. The session re-asks GitHub on every tick and re-emits the graph only
    // when the answer differs, so an unchanged minute costs one request and moves no card.
    LaunchedEffect(graphState is GraphState.Loaded, status.githubConfigured, derivePr) {
        if (graphState !is GraphState.Loaded || !status.githubConfigured || !derivePr) {
            return@LaunchedEffect
        }
        while (true) {
            delay(PR_STATUS_TTL)
            holder.session.refreshPrStatuses()
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

    var placedRelayouts by remember { mutableStateOf(0) }
    LaunchedEffect(projected, filterState.groupBy, relayouts) {
        val key = holder.session.layoutCacheKey()
        val relayout = relayouts != placedRelayouts
        placedRelayouts = relayouts
        val snapshot = holder.positions.get()
        val next = withContext(Dispatchers.Default) {
            placeGraph(projected, filterState.groupBy, key, snapshot, relayout = relayout)
        }
        // A drag that landed while the layout ran wrote to the same key. It is the newer intent,
        // so it wins over the positions this pass computed from the pre-drag snapshot.
        val before = snapshot.byKey[key].orEmpty()
        val dragged = holder.positions.get().byKey[key].orEmpty().filterNot { before[it.key] == it.value }
        if (next.snapshot != snapshot || dragged.isNotEmpty()) {
            val saved = next.snapshot.byKey[key].orEmpty() + dragged
            holder.positions.set(PositionSnapshot(holder.positions.get().byKey + (key to saved)))
        }
        placement = if (dragged.isEmpty()) {
            next
        } else {
            val positions = next.positions + dragged
            next.copy(positions = positions, groups = groupBoxesOf(projected, filterState.groupBy, positions))
        }
    }

    // The tuck-under slide. It runs against the positions on screen and saves what it lands on,
    // because a relation the user drew is their arrangement, not one the next pass may re-derive.
    LaunchedEffect(tuck) {
        val settled = tuck ?: return@LaunchedEffect
        slideTo(placement.positions, settled) { frame ->
            placement = placement.copy(positions = frame)
                .let { it.copy(groups = groupBoxesOf(projected, filterState.groupBy, frame)) }
        }
        holder.session.savePositions(settled)
        tuck = null
    }

    // A reload of the same query keeps the viewport. A different query gets a fresh fit.
    var fittedIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(placement) {
        val ids = placement.positions.keys
        if (ids.isEmpty()) return@LaunchedEffect
        val shared = ids.count { it in fittedIds }
        val union = ids.size + fittedIds.size - shared
        fittedIds = ids
        if (shared == 0 || (union - shared) * 2 > union) canvasState.fitToContent()
    }

    // A pasted issue URL names one issue. The filters load its team; this points the graph at it.
    // Declared after the fit so it wins the frame they share.
    LaunchedEffect(placement, filterState.focusIssueId) {
        val id = filterState.focusIssueId ?: return@LaunchedEffect
        // A stacked issue has no position of its own; the pile it draws in holds one.
        val position = placement.positions[slotOf(id, stackIndex(visibleStacks(projected)))]
            ?: return@LaunchedEffect
        selection = setOf(id)
        canvasState.centerOn(
            Offset(
                position.x + GraphCanvasDefaults.NodeWidth / 2f,
                position.y + GraphCanvasDefaults.NodeHeight / 2f,
            )
        )
    }

    LaunchedEffect(env.commands) {
        env.commands.commands.collect { command ->
            when (command) {
                AppCommand.RELOAD -> holder.session.reload()
                AppCommand.RELAYOUT -> {
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

    fun mutate(
        detail: String,
        confirmLabel: String,
        after: () -> Unit = {},
        run: suspend () -> Unit,
    ) {
        pending = Pending(detail, confirmLabel, run, after)
    }

    /**
     * The arrange answer to a new "A blocks B": B and everything it blocks tuck in under A,
     * against the positions on screen, and nothing else moves. The result is the user's own
     * arrangement, so it is saved like a drop rather than left for the next placement pass to
     * re-derive.
     */
    fun tuckUnder(blocker: String, blocked: String) {
        val index = stackIndex(visibleStacks(projected))
        val from = slotOf(blocker, index)
        val to = slotOf(blocked, index)
        if (from == to) return
        val settled = relayoutDescendants(
            current = placement.positions,
            nodes = layoutNodesOf(projected),
            edges = layoutEdgesOf(projected),
            newEdge = LayoutEdge(from, to, LayoutEdgeKind.BLOCKS),
        )
        if (settled == placement.positions) return
        tuck = settled
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
            counts = "${drawn.nodes.size} issues, ${drawn.edges.size} relations",
            prStatusFailed = prStatusFailed,
            groupBy = filterState.groupBy,
            onGroupBy = holder.filters::setGroupBy,
            showRelated = showRelated,
            onShowRelated = holder.session::setShowRelatedEdges,
            showDuplicates = showDuplicates,
            onShowDuplicates = holder.session::setShowDuplicates,
            derivePr = derivePr,
            onDerivePr = holder.session::setDerivePrRelations,
            githubConnected = status.githubConfigured,
            onConnectGithub = { githubDialog = true },
            showCrossLinks = showCrossLinks,
            onShowCrossLinks = { showCrossLinks = it },
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
                    graph = drawn,
                    positions = placement.positions,
                    modifier = Modifier.fillMaxSize(),
                    readySet = readySet,
                    prStatuses = prStatuses,
                    users = reference.users,
                    states = states,
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
                            mutate(
                                detail = relationDetail(from, to, type, reversed),
                                confirmLabel = "Create",
                                // Only a blocks relation implies a place on the board. A reversed
                                // one reads "from is blocked by to", so `to` is the blocker.
                                after = {
                                    if (type == RelationType.BLOCKS) {
                                        if (reversed) tuckUnder(to, from) else tuckUnder(from, to)
                                    }
                                },
                            ) {
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
                            // The whole visible layout is persisted, not just the cards that
                            // moved. A node the snapshot does not name counts as auto-placed, so
                            // the next placement pass would re-derive it and cascade it out of
                            // the way of the one the user just dropped. A drop is final.
                            val placed = placement.positions + moved
                            holder.session.savePositions(placed)
                            placement = placement.copy(positions = placed)
                                .let { it.copy(groups = groupBoxesOf(projected, filterState.groupBy, placed)) }
                        },
                        onSelectionChange = { selection = it },
                        onSetState = { id, stateId, stateName ->
                            mutate("This moves $id to $stateName in Linear.", "Change") {
                                holder.session.setState(id, stateId)
                            }
                        },
                        onSetPriority = { id, priority ->
                            val label = PRIORITY_LABELS[priority] ?: "no priority"
                            mutate("This sets $id to $label in Linear.", "Change") {
                                holder.session.setPriority(id, priority)
                            }
                        },
                        onSetEstimate = { id, estimate ->
                            val detail = if (estimate == null) {
                                "This clears the estimate on $id in Linear."
                            } else {
                                "This sets the estimate on $id to $estimate in Linear."
                            }
                            mutate(detail, "Change") { holder.session.setEstimate(id, estimate) }
                        },
                        onAttachPr = { id, url ->
                            mutate("This links $url to $id in Linear.", "Link") {
                                holder.session.attachPr(id, url)
                            }
                        },
                        onRemoveFromProject = { id ->
                            mutate("This removes $id from its project in Linear.", "Remove") {
                                holder.session.removeFromProject(id)
                            }
                        },
                        onRefused = env.beep,
                        onRelayout = {
                            relayouts++
                        },
                        onReload = { holder.session.reload() },
                    ),
                    underlay = {
                        GroupUnderlay(
                            groups = placement.groups,
                            // Interact gives every left drag to the pan, labels included, so the
                            // area drag is an Arrange tool and only shows its grip there.
                            draggable = canvasState.mode == CanvasMode.ARRANGE,
                            onDrag = { label, delta ->
                                areaDrag += delta
                                val ids = idsIn(projected, filterState.groupBy, label)
                                val moved = moveGroup(placement.positions, ids, delta)
                                placement = placement.copy(positions = moved)
                                    .let { it.copy(groups = groupBoxesOf(projected, filterState.groupBy, moved)) }
                            },
                            onDragEnd = { label ->
                                val stored = holder.positions.get()
                                    .byKey[holder.session.layoutCacheKey()].orEmpty()[groupOffsetKey(label)]
                                holder.session.savePositions(
                                    placement.positions + (
                                        groupOffsetKey(label) to Position(
                                            (stored?.x ?: 0f) + areaDrag.x,
                                            (stored?.y ?: 0f) + areaDrag.y,
                                        )
                                        ),
                                )
                                areaDrag = Offset.Zero
                            },
                        )
                    },
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
                        request.after()
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { githubDialog = false },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                // The card is not a click target, so without this the backdrop takes every click
                // that lands between its controls and closes the dialog under the pointer.
                Modifier.width(460.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            ) {
                GithubCard(env, scope, dismissLabel = "Cancel") {
                    githubDialog = false
                    onAuthChanged()
                    // The graph is already loaded. The new token only owes it the pull requests.
                    holder.session.refreshPrStatuses()
                }
            }
        }
    }
}

/** Row two: how the graph is drawn, and where its filters came from. */
@Composable
private fun ViewToolbar(
    counts: String,
    prStatusFailed: Boolean,
    groupBy: GraphGrouping,
    onGroupBy: (GraphGrouping) -> Unit,
    showRelated: Boolean,
    onShowRelated: (Boolean) -> Unit,
    showDuplicates: Boolean,
    onShowDuplicates: (Boolean) -> Unit,
    derivePr: Boolean,
    onDerivePr: (Boolean) -> Unit,
    githubConnected: Boolean,
    onConnectGithub: () -> Unit,
    showCrossLinks: Boolean,
    onShowCrossLinks: (Boolean) -> Unit,
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
                SwimOption("milestone", "Milestone"),
            ),
            onSelect = { value ->
                onGroupBy(
                    when (value) {
                        "team" -> GraphGrouping.TEAM
                        "project" -> GraphGrouping.PROJECT
                        "label" -> GraphGrouping.LABEL
                        "milestone" -> GraphGrouping.MILESTONE
                        else -> GraphGrouping.NONE
                    }
                )
            },
            width = 130.dp,
            anyLabel = "None",
        )
        SwimCheckbox("Related edges", showRelated, onShowRelated)
        SwimCheckbox("Duplicates", showDuplicates, onShowDuplicates)
        // Without a GitHub token there are no pull requests to read. The box then offers the
        // connect dialog, because a user who already had Linear never sees the login card.
        SwimCheckbox(
            label = "Derive relations from PR stacks",
            checked = derivePr && githubConnected,
            onCheckedChange = onDerivePr,
            enabled = githubConnected,
            hint = if (githubConnected) null else "Click to connect GitHub",
            onDisabledClick = onConnectGithub,
        )
        // Only milestone areas hide their crossings, so only that mode offers the switch.
        if (groupBy == GraphGrouping.MILESTONE) {
            SwimCheckbox("Cross-milestone links", showCrossLinks, onShowCrossLinks)
        }
        // A PR chip with no badge otherwise reads as "no reviews and no checks".
        if (prStatusFailed) Text("PR status unavailable", color = Swim.Amber, fontSize = 11.sp)
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
internal fun GroupUnderlay(
    groups: List<GroupBox>,
    draggable: Boolean = false,
    onDrag: (label: String, delta: Offset) -> Unit = { _, _ -> },
    onDragEnd: (label: String) -> Unit = {},
) {
    if (groups.isEmpty()) return
    val density = LocalDensity.current.density
    Canvas(Modifier.fillMaxSize()) {
        // Canvas units are dp; this draw scope is pixels.
        groups.forEach { group ->
            val topLeft = Offset(group.x * density, group.y * density)
            val size = Size(group.width * density, group.height * density)
            val radius = CornerRadius(10f * density)
            drawRoundRect(Swim.Card.copy(alpha = 0.5f), topLeft, size, radius)
            drawRoundRect(Swim.Border, topLeft, size, radius, style = Stroke(1f * density))
        }
    }
    // The label is a real composable, not drawn text, because it is the grip for the area drag.
    Box(Modifier.fillMaxSize()) {
        groups.forEach { group ->
            key(group.label) {
                GroupLabel(
                    group = group,
                    draggable = draggable,
                    density = density,
                    onDrag = { onDrag(group.label, it) },
                    onDragEnd = { onDragEnd(group.label) },
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(
    group: GroupBox,
    draggable: Boolean,
    density: Float,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    ((group.x + 8f) * density).roundToInt(),
                    ((group.y + 5f) * density).roundToInt(),
                )
            }
            .wrapContentSize(unbounded = true, align = Alignment.TopStart)
            .background(
                if (hovered && draggable) Swim.CardHover else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .then(
                if (draggable) {
                    Modifier
                        .hoverable(interaction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(group.label) {
                            detectDragGestures(
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd,
                            ) { change, amount ->
                                change.consume()
                                onDrag(amount / density)
                            }
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = group.label,
            color = if (hovered && draggable) Swim.Text else Swim.TextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
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
