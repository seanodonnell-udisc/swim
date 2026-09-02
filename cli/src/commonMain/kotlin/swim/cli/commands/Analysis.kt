package swim.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import swim.cli.Out
import swim.cli.Runtime
import swim.cli.ScopedCommand
import swim.cli.SwimCommand
import swim.cli.bold
import swim.cli.cyan
import swim.cli.formatIssueLine
import swim.cli.gray
import swim.cli.green
import swim.cli.blue
import swim.cli.outJson
import swim.cli.parsePositiveInt
import swim.cli.progressBar
import swim.cli.red
import swim.cli.resolveIssueRef
import swim.cli.teamOf
import swim.cli.toScopeJson
import swim.cli.truncate
import swim.cli.wireName
import swim.cli.yellow
import swim.core.analysis.analyzePipeline
import swim.core.analysis.extractSubgraph
import swim.core.analysis.filterActiveBlockers
import swim.core.analysis.findBlockerChain
import swim.core.analysis.findDownstreamIssues
import swim.core.analysis.findUnblockedIssues
import swim.core.analysis.getCrossTeamBlocks
import swim.core.analysis.isDone
import swim.core.analysis.scoreAndRankIssues
import swim.core.mermaid.generateFlowchart
import swim.core.mermaid.generateTeamDependencies
import swim.core.model.CrossTeamBlock
import swim.core.model.DiagramOptions
import swim.core.model.DownstreamResult
import swim.core.model.FilterOptions
import swim.core.model.GraphData
import swim.core.model.IssueNode
import swim.core.model.NotFoundError
import swim.core.model.PRIORITY_LABELS

/** Reads the graph and reports the size on stderr. */
private suspend fun loadGraph(filters: FilterOptions): GraphData {
    Out.status("Fetching issues and relations…")
    val graph = Runtime.linear.getIssuesWithRelations(filters)
    Out.status("${graph.nodes.size} issues, ${graph.edges.size} relations")
    return graph
}

/** `swim status` — per-team progress and the blocks that cross teams. */
class StatusCommand : ScopedCommand("status") {
    override fun help(context: Context) =
        "Per-team progress: done, in progress, ready, blocked; plus cross-team blocks"

    private val mermaid by option("--mermaid", help = "print a team dependency diagram instead").flag()
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        // Progress is meaningless without the finished work.
        val filters = resolveScope().copy(includeCompleted = true)
        val graph = loadGraph(filters)
        val stages = analyzePipeline(graph)
        val crossTeam = getCrossTeamBlocks(graph)

        if (mermaid) {
            Out.line(generateTeamDependencies(graph.nodes, graph.edges))
            return
        }
        if (json) {
            val data = buildJsonObject {
                put(
                    "teams",
                    buildJsonArray {
                        for (stage in stages) {
                            add(
                                buildJsonObject {
                                    put("team", stage.team)
                                    put("total", stage.issues.size)
                                    put("completed", stage.completed)
                                    put("inProgress", stage.inProgress)
                                    put("ready", stage.ready)
                                    put("blocked", stage.blocked)
                                }
                            )
                        }
                    }
                )
                put(
                    "crossTeamBlocks",
                    outJson.encodeToJsonElement(ListSerializer(CrossTeamBlock.serializer()), crossTeam),
                )
            }
            Out.json("status", filters.toScopeJson(), data, graph.nodes.size)
            return
        }

        var totalDone = 0
        for (stage in stages) {
            val total = stage.issues.size
            totalDone += stage.completed
            val percent = if (total > 0) (stage.completed * 100.0 / total).toIntRounded() else 0
            Out.line("\n${cyan(bold(stage.team))}  ${progressBar(percent, 20)} $percent% (${stage.completed}/$total)")
            Out.line(
                "  ${green("${stage.completed} done")} · ${blue("${stage.inProgress} in progress")} · " +
                    "${yellow("${stage.ready} ready")} · ${red("${stage.blocked} blocked")}"
            )
        }
        val total = graph.nodes.size
        val percent = if (total > 0) (totalDone * 100.0 / total).toIntRounded() else 0
        Out.line("\n${bold("Overall")}  ${progressBar(percent, 30)} $percent% ($totalDone/$total)")

        if (crossTeam.isNotEmpty()) {
            Out.line("\n${bold("Cross-team blocks")}")
            for (block in crossTeam) Out.line("  ${block.fromTeam} → ${block.toTeam}: ${block.count}")
        }
    }
}

/** `swim ready` — the issues that can start now. */
class ReadyCommand : ScopedCommand("ready") {
    override fun help(context: Context) =
        "Issues that can start now: not done, not started, no active blockers"

    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val filters = resolveScope()
        val ready = findUnblockedIssues(loadGraph(filters))

        if (json) {
            val data = outJson.encodeToJsonElement(ListSerializer(IssueNode.serializer()), ready)
            Out.json("ready", filters.toScopeJson(), data, ready.size)
            return
        }
        if (ready.isEmpty()) {
            Out.status("No issues are ready to start.")
            return
        }

        val byTeam = LinkedHashMap<String, MutableList<IssueNode>>()
        for (issue in ready) byTeam.getOrPut(issue.team) { mutableListOf() }.add(issue)
        for ((team, issues) in byTeam) {
            Out.line(bold("\n$team (${issues.size})"))
            for (issue in issues) Out.line("  " + formatIssueLine(issue))
        }
    }
}

/** `swim next` — the ready issues that pay off most. */
class NextCommand : ScopedCommand("next") {
    override fun help(context: Context) =
        "Ready issues ranked by priority, how much they unblock, and cross-team leverage"

    private val count by option("-n", "--count", metavar = "N", help = "how many to show")
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val filters = resolveScope()
        val limit = parsePositiveInt(count, "--count", 5)
        val ranked = scoreAndRankIssues(loadGraph(filters), limit)

        if (json) {
            val data = buildJsonArray {
                for (scored in ranked) {
                    add(
                        buildJsonObject {
                            outJson.encodeToJsonElement(IssueNode.serializer(), scored.node).jsonObject
                                .forEach { (key, value) -> put(key, value) }
                            put("score", scored.score)
                            put("reason", scored.reason)
                        }
                    )
                }
            }
            Out.json("next", filters.toScopeJson(), data, ranked.size)
            return
        }
        if (ranked.isEmpty()) {
            Out.status("No issues are ready to start.")
            return
        }
        ranked.forEachIndexed { index, scored ->
            Out.line("${bold("${index + 1}.")} ${formatIssueLine(scored.node)}")
            Out.line("   ${gray("score ${scored.score} · ${scored.reason}")}")
        }
    }
}

/** `swim blockers` — everything that blocks one issue. */
class BlockersCommand : SwimCommand("blockers") {
    override fun help(context: Context) = "Everything blocking an issue, transitively (identifier or URL)"

    private val issue by argument("issue")
    private val team by option("-t", "--team", metavar = "KEYS", help = "also fetch these teams, for chains that cross teams")
    private val depth by option("--depth", metavar = "N", help = "maximum chain depth")
    private val mermaid by option("--mermaid", help = "print the blocker chain as a Mermaid diagram").flag()
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val identifier = resolveIssueRef(issue)
        val maxDepth = parsePositiveInt(depth, "--depth", 10)
        val graph = loadGraphFor(listOf(identifier), team)
        val chain = findBlockerChain(graph, identifier, maxDepth)
        val active = filterActiveBlockers(chain)

        if (mermaid) {
            val ids = (listOf(identifier) + chain.map { it.identifier }).toSet()
            val subgraph = extractSubgraph(graph, ids)
            Out.line(generateFlowchart(subgraph.nodes, subgraph.edges, DiagramOptions(direction = "BT")))
            return
        }
        if (json) {
            val data = buildJsonArray {
                for (blocker in chain) {
                    add(
                        buildJsonObject {
                            put("identifier", blocker.identifier)
                            put("depth", blocker.depth)
                            blocker.node?.let { put("title", it.title); put("state", it.state) }
                            blocker.stateType?.let { put("stateType", it.wireName()) }
                            put("active", !isDone(blocker.stateType))
                            put("inScope", blocker.node != null)
                        }
                    )
                }
            }
            val scope = buildJsonObject { put("issue", identifier); put("activeCount", active.size) }
            Out.json("blockers", scope, data, chain.size)
            return
        }
        if (chain.isEmpty()) {
            Out.line("${green("✓")} $identifier has no blockers.")
            return
        }
        Out.line(bold("${active.size} active of ${chain.size} blockers for $identifier"))
        for (blocker in chain) {
            val indent = "  ".repeat(blocker.depth)
            val mark = if (isDone(blocker.stateType)) green("✓") else red("●")
            val title = blocker.node?.let { truncate(it.title, 60) }
                ?: gray("(outside fetched teams; add --team)")
            val state = blocker.node?.state ?: blocker.stateType?.wireName() ?: "unknown"
            Out.line("$indent$mark ${cyan(blocker.identifier)} $title ${gray("[$state]")}")
        }
    }
}

/** `swim downstream` — everything that finishing the given issues would unblock. */
class DownstreamCommand : SwimCommand("downstream") {
    override fun help(context: Context) =
        "Everything that finishing the given issues would unblock, transitively"

    private val issues by argument("issues").multiple(required = true)
    private val team by option("-t", "--team", metavar = "KEYS", help = "also fetch these teams, for chains that cross teams")
    private val depth by option("--depth", metavar = "N", help = "maximum chain depth")
    private val mermaid by option("--mermaid", help = "print the downstream graph as a Mermaid diagram").flag()
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val identifiers = issues.map { resolveIssueRef(it) }
        val maxDepth = parsePositiveInt(depth, "--depth", 10)
        val graph = loadGraphFor(identifiers, team)
        val result = findDownstreamIssues(graph, identifiers, maxDepth)

        if (mermaid) {
            Out.line(
                generateFlowchart(result.downstreamNodes, result.downstreamEdges, DiagramOptions(direction = "TD"))
            )
            return
        }
        if (json) {
            val data = outJson.encodeToJsonElement(DownstreamResult.serializer(), result)
            val scope = buildJsonObject {
                put("issues", buildJsonArray { identifiers.forEach { add(it) } })
            }
            Out.json("downstream", scope, data, result.impactSummary.totalUnblocked)
            return
        }

        val summary = result.impactSummary
        if (summary.totalUnblocked == 0) {
            Out.line("Nothing depends on ${identifiers.joinToString(", ")}.")
            return
        }
        Out.line(bold("Finishing ${identifiers.joinToString(", ")} unblocks ${summary.totalUnblocked} issues"))
        Out.line("  by team: " + summary.byTeam.entries.joinToString(" · ") { "${it.key} ${it.value}" })
        Out.line(
            "  by priority: " + summary.byPriority.entries.sortedBy { it.key }
                .joinToString(" · ") { "${PRIORITY_LABELS[it.key]} ${it.value}" }
        )
        Out.line()
        val sources = identifiers.toSet()
        for (node in result.downstreamNodes) {
            if (node.identifier in sources) continue
            Out.line("  " + formatIssueLine(node))
        }
    }
}

/** Fetches the teams the identifiers name, plus any extra `--team`, with completed work included. */
private suspend fun loadGraphFor(identifiers: List<String>, extraTeams: String?): GraphData {
    val teams = LinkedHashSet(identifiers.map(::teamOf))
    extraTeams?.split(",")?.forEach { key -> key.trim().takeIf { it.isNotEmpty() }?.let { teams.add(it.uppercase()) } }
    val graph = loadGraph(FilterOptions(team = teams.joinToString(","), includeCompleted = true))
    val present = graph.nodes.mapTo(mutableSetOf()) { it.identifier }
    val missing = identifiers.filterNot { it in present }
    if (missing.isNotEmpty()) throw NotFoundError("Issue not found: ${missing.joinToString(", ")}")
    return graph
}

/** Rounds a percentage the way the original CLI does: half goes up. */
private fun Double.toIntRounded(): Int = kotlin.math.round(this).toInt()
