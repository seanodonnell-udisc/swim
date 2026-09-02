package swim.core.analysis

import swim.core.model.BlockerChainNode
import swim.core.model.CrossTeamBlock
import swim.core.model.DownstreamResult
import swim.core.model.GraphData
import swim.core.model.ImpactSummary
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.PRIORITY_LABELS
import swim.core.model.PipelineStage
import swim.core.model.RelationType
import swim.core.model.ScoredIssue
import swim.core.model.WorkflowStateType

/** True for a completed or canceled state type. */
fun isDone(stateType: WorkflowStateType?): Boolean =
    stateType == WorkflowStateType.COMPLETED || stateType == WorkflowStateType.CANCELED

/** True for a started state type. */
fun isStarted(stateType: WorkflowStateType?): Boolean = stateType == WorkflowStateType.STARTED

/** issue -> issues it blocks. */
fun buildBlocksMap(edges: List<IssueEdge>): Map<String, Set<String>> {
    val blocks = LinkedHashMap<String, LinkedHashSet<String>>()
    for (edge in edges) {
        if (edge.type != RelationType.BLOCKS) continue
        blocks.getOrPut(edge.from) { LinkedHashSet() }.add(edge.to)
    }
    return blocks
}

/** issue -> issues blocking it. */
fun buildBlockedByMap(edges: List<IssueEdge>): Map<String, Set<String>> {
    val blockedBy = LinkedHashMap<String, LinkedHashSet<String>>()
    for (edge in edges) {
        if (edge.type != RelationType.BLOCKS) continue
        blockedBy.getOrPut(edge.to) { LinkedHashSet() }.add(edge.from)
    }
    return blockedBy
}

/** State type of every issue we know about: fetched nodes plus external blockers. */
fun buildStateTypeMap(graphData: GraphData): Map<String, WorkflowStateType> {
    val states = LinkedHashMap<String, WorkflowStateType>()
    for (node in graphData.nodes) states[node.identifier] = node.stateType
    for ((id, type) in graphData.externalBlockerStates) states[id] = type
    return states
}

private fun buildNodeMap(nodes: List<IssueNode>): Map<String, IssueNode> = nodes.associateBy { it.identifier }

/** True when any blocker of `identifier` is not done; an unknown blocker state counts as active. */
fun hasActiveBlockers(
    identifier: String,
    blockedBy: Map<String, Set<String>>,
    stateTypes: Map<String, WorkflowStateType>,
): Boolean {
    val blockers = blockedBy[identifier] ?: return false
    for (blockerId in blockers) {
        if (!isDone(stateTypes[blockerId])) return true
    }
    return false
}

/** Identifiers of fetched issues that are not done, not started, and not blocked. */
fun findReadySet(graphData: GraphData): Set<String> {
    val blockedBy = buildBlockedByMap(graphData.edges)
    val stateTypes = buildStateTypeMap(graphData)
    val ready = LinkedHashSet<String>()
    for (node in graphData.nodes) {
        if (isDone(node.stateType) || isStarted(node.stateType)) continue
        if (!hasActiveBlockers(node.identifier, blockedBy, stateTypes)) ready.add(node.identifier)
    }
    return ready
}

/** Nodes in `identifiers` plus the edges among them. */
fun extractSubgraph(graphData: GraphData, identifiers: Set<String>): GraphData =
    GraphData(
        nodes = graphData.nodes.filter { it.identifier in identifiers },
        edges = graphData.edges.filter { it.from in identifiers && it.to in identifiers },
    )

private class MutableStage(val team: String) {
    val issues = mutableListOf<IssueNode>()
    var completed = 0
    var inProgress = 0
    var blocked = 0
    var ready = 0
}

/** Per-team done / in progress / ready / blocked counts, teams that block others first. */
fun analyzePipeline(graphData: GraphData): List<PipelineStage> {
    val blockedBy = buildBlockedByMap(graphData.edges)
    val blocks = buildBlocksMap(graphData.edges)
    val stateTypes = buildStateTypeMap(graphData)
    val nodeMap = buildNodeMap(graphData.nodes)

    val stagesByTeam = LinkedHashMap<String, MutableStage>()
    for (node in graphData.nodes) {
        val stage = stagesByTeam.getOrPut(node.team) { MutableStage(node.team) }
        stage.issues.add(node)

        when {
            isDone(node.stateType) -> stage.completed++
            isStarted(node.stateType) -> stage.inProgress++
            hasActiveBlockers(node.identifier, blockedBy, stateTypes) -> stage.blocked++
            else -> stage.ready++
        }
    }

    val crossTeamBlocking = HashMap<String, Int>()
    for ((team, stage) in stagesByTeam) {
        var count = 0
        for (issue in stage.issues) {
            for (id in blocks[issue.identifier] ?: emptySet()) {
                val target = nodeMap[id]
                if (target != null && target.team != team) count++
            }
        }
        crossTeamBlocking[team] = count
    }

    return stagesByTeam.values
        .map { stage ->
            PipelineStage(
                name = stage.team,
                team = stage.team,
                issues = stage.issues.toList(),
                completed = stage.completed,
                inProgress = stage.inProgress,
                blocked = stage.blocked,
                ready = stage.ready,
            )
        }
        .sortedByDescending { crossTeamBlocking[it.team] ?: 0 }
}

/** Blocking relations that cross a team boundary, aggregated by (fromTeam, toTeam). */
fun getCrossTeamBlocks(graphData: GraphData): List<CrossTeamBlock> {
    val nodeMap = buildNodeMap(graphData.nodes)
    val counts = LinkedHashMap<Pair<String, String>, Int>()

    for (edge in graphData.edges) {
        if (edge.type != RelationType.BLOCKS) continue
        val from = nodeMap[edge.from] ?: continue
        val to = nodeMap[edge.to] ?: continue
        if (from.team == to.team) continue
        val key = from.team to to.team
        counts[key] = (counts[key] ?: 0) + 1
    }

    return counts.map { (key, count) -> CrossTeamBlock(fromTeam = key.first, toTeam = key.second, count = count) }
}

private fun priorityRank(priority: Int): Int = if (priority == 0) 5 else priority

/** Issues ready to start, urgent first. */
fun findUnblockedIssues(graphData: GraphData): List<IssueNode> {
    val ready = findReadySet(graphData)
    return graphData.nodes
        .filter { it.identifier in ready }
        .sortedWith(compareBy({ priorityRank(it.priority) }, { it.identifier }))
}

/** Every issue blocking the target, transitively. Cycle-safe; each blocker appears once. */
fun findBlockerChain(graphData: GraphData, targetIdentifier: String, maxDepth: Int = 10): List<BlockerChainNode> {
    val blockedBy = buildBlockedByMap(graphData.edges)
    val stateTypes = buildStateTypeMap(graphData)
    val nodeMap = buildNodeMap(graphData.nodes)

    val chain = LinkedHashMap<String, BlockerChainNode>()
    val visited = HashSet<String>()

    fun walk(id: String, depth: Int) {
        if (depth > maxDepth || id in visited) return
        visited.add(id)
        for (blockerId in blockedBy[id] ?: emptySet()) {
            val candidate = BlockerChainNode(
                identifier = blockerId,
                depth = depth,
                stateType = stateTypes[blockerId],
                node = nodeMap[blockerId],
            )
            val existing = chain[blockerId]
            if (existing == null || candidate.depth < existing.depth) chain[blockerId] = candidate
            walk(blockerId, depth + 1)
        }
    }

    walk(targetIdentifier.uppercase(), 1)
    return chain.values.toList()
}

/** Blockers in the chain that are not yet done. */
fun filterActiveBlockers(blockers: List<BlockerChainNode>): List<BlockerChainNode> =
    blockers.filter { !isDone(it.stateType) }

/** Every issue that finishing the sources would unblock, transitively. */
fun findDownstreamIssues(graphData: GraphData, sourceIdentifiers: List<String>, maxDepth: Int = 10): DownstreamResult {
    val blocks = buildBlocksMap(graphData.edges)
    val sources = sourceIdentifiers.map { it.uppercase() }
    val sourceSet = sources.toSet()

    val reached = HashSet<String>()
    val edgeKeys = HashSet<String>()
    val downstreamEdges = mutableListOf<IssueEdge>()

    fun walk(id: String, depth: Int) {
        if (depth > maxDepth || id in reached) return
        reached.add(id)
        for (blockedId in blocks[id] ?: emptySet()) {
            val key = "$id>$blockedId"
            if (edgeKeys.add(key)) downstreamEdges.add(IssueEdge(from = id, to = blockedId, type = RelationType.BLOCKS))
            walk(blockedId, depth + 1)
        }
    }
    for (source in sources) walk(source, 0)

    val downstreamNodes = graphData.nodes.filter { it.identifier in reached }
    val impactNodes = downstreamNodes.filter { it.identifier !in sourceSet }

    val byTeam = LinkedHashMap<String, Int>()
    val byPriority = LinkedHashMap<Int, Int>()
    for (node in impactNodes) {
        byTeam[node.team] = (byTeam[node.team] ?: 0) + 1
        byPriority[node.priority] = (byPriority[node.priority] ?: 0) + 1
    }

    return DownstreamResult(
        sourceIssues = sources,
        downstreamNodes = downstreamNodes,
        downstreamEdges = downstreamEdges,
        impactSummary = ImpactSummary(totalUnblocked = impactNodes.size, byTeam = byTeam, byPriority = byPriority),
    )
}

/** Ready issues ranked by priority, fan-out, and cross-team leverage. */
fun scoreAndRankIssues(graphData: GraphData, count: Int = 5): List<ScoredIssue> {
    val blocks = buildBlocksMap(graphData.edges)
    val nodeMap = buildNodeMap(graphData.nodes)
    val ready = findReadySet(graphData)

    val scored = mutableListOf<ScoredIssue>()
    for (node in graphData.nodes) {
        if (node.identifier !in ready) continue

        var score = 0
        val reasons = mutableListOf<String>()

        when (node.priority) {
            1 -> { score += 100; reasons.add("urgent") }
            2 -> { score += 50; reasons.add("high priority") }
            3 -> score += 25
        }

        val blocking = blocks[node.identifier]
        if (!blocking.isNullOrEmpty()) {
            score += blocking.size * 20
            reasons.add("unblocks ${blocking.size} issues")

            var crossTeam = 0
            for (id in blocking) {
                val target = nodeMap[id]
                if (target != null && target.team != node.team) crossTeam++
            }
            if (crossTeam > 0) {
                score += crossTeam * 30
                reasons.add("enables $crossTeam cross-team issues")
            }
        }

        scored.add(ScoredIssue(node = node, score = score, reason = reasons.joinToString(", ").ifEmpty { "ready to start" }))
    }

    return scored.sortedByDescending { it.score }.take(count)
}

enum class GroupBy { STATE, PRIORITY, ASSIGNEE, TEAM, PROJECT, MILESTONE }

/** Groups issues by the given key, each group sorted with "no priority" sinking last. */
fun groupIssues(nodes: List<IssueNode>, groupBy: GroupBy): Map<String, List<IssueNode>> {
    fun keyOf(n: IssueNode): String = when (groupBy) {
        GroupBy.PRIORITY -> PRIORITY_LABELS[n.priority] ?: n.priority.toString()
        GroupBy.ASSIGNEE -> n.assignee ?: "Unassigned"
        GroupBy.TEAM -> n.team
        GroupBy.PROJECT -> n.project ?: "No project"
        GroupBy.MILESTONE -> n.milestone ?: "No milestone"
        GroupBy.STATE -> n.state
    }

    val groups = LinkedHashMap<String, MutableList<IssueNode>>()
    for (node in nodes) groups.getOrPut(keyOf(node)) { mutableListOf() }.add(node)
    return groups.mapValues { (_, issues) -> issues.sortedBy { priorityRank(it.priority) } }
}

/**
 * Removes nodes on the duplicate side of a DUPLICATE edge, and every edge touching them.
 * `from` is the duplicate: a relation reads source-to-target, so `from` duplicates `to`, and
 * `to` is the canonical issue that survives.
 */
fun hideDuplicates(graph: GraphData): GraphData {
    val duplicateIds = graph.edges.filter { it.type == RelationType.DUPLICATE }.map { it.from }.toSet()
    return GraphData(
        nodes = graph.nodes.filter { it.identifier !in duplicateIds },
        edges = graph.edges.filter { it.from !in duplicateIds && it.to !in duplicateIds },
        externalBlockerStates = graph.externalBlockerStates,
    )
}
