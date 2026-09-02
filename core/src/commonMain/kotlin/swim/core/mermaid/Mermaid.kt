package swim.core.mermaid

import swim.core.model.DiagramOptions
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.PRIORITY_LABELS
import swim.core.model.RelationType

val STATE_STYLES: Map<String, String> = mapOf(
    "done" to "fill:#36b37e,stroke:#1e7e34",
    "completed" to "fill:#36b37e,stroke:#1e7e34",
    "canceled" to "fill:#616161,stroke:#424242",
    "cancelled" to "fill:#616161,stroke:#424242",
    "in progress" to "fill:#fff9c4,stroke:#f9a825",
    "in review" to "fill:#c8e6c9,stroke:#388e3c",
    "paused" to "fill:#bbdefb,stroke:#1976d2",
    "in limbo" to "fill:#bbdefb,stroke:#1976d2",
    "todo" to "fill:#ffffff,stroke:#bdbdbd",
    "backlog" to "fill:#e0e0e0,stroke:#9e9e9e",
)

private fun escapeLabel(text: String): String = text
    .replace("\"", "'")
    .replace("[", "(")
    .replace("]", ")")
    .replace("{", "(")
    .replace("}", ")")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun truncate(text: String, maxLength: Int = 40): String =
    if (text.length <= maxLength) text else text.substring(0, maxLength - 3) + "..."

private fun nodeId(identifier: String): String = identifier.replace("-", "_")

private fun formatNode(node: IssueNode, showState: Boolean, showPriority: Boolean): String {
    var label = "${node.identifier}: ${truncate(escapeLabel(node.title))}"

    if (showState || showPriority) {
        val metadata = mutableListOf<String>()
        if (showState) metadata.add(node.state)
        if (showPriority && node.priority > 0) metadata.add(PRIORITY_LABELS[node.priority] ?: "")
        val nonBlank = metadata.filter { it.isNotEmpty() }
        if (nonBlank.isNotEmpty()) label += "<br/><small>${nonBlank.joinToString(" | ")}</small>"
    }

    return "${nodeId(node.identifier)}[\"$label\"]"
}

private fun formatEdge(edge: IssueEdge): String {
    val fromId = nodeId(edge.from)
    val toId = nodeId(edge.to)
    return when (edge.type) {
        RelationType.BLOCKS -> "$fromId -->|blocks| $toId"
        RelationType.RELATED -> "$fromId -.-|related| $toId"
        RelationType.DUPLICATE -> "$fromId -.->|duplicate| $toId"
    }
}

/** Generates a Mermaid flowchart from nodes and edges. */
fun generateFlowchart(nodes: List<IssueNode>, edges: List<IssueEdge>, options: DiagramOptions = DiagramOptions()): String {
    val (direction, groupBy, showState, showPriority) = options
    val mermaid = StringBuilder("flowchart $direction\n")

    if (groupBy != "none") {
        val groups = LinkedHashMap<String, MutableList<IssueNode>>()
        for (node in nodes) {
            val groupKey = when (groupBy) {
                "team" -> node.team
                "project" -> node.project ?: "No Project"
                "label" -> node.labels.firstOrNull() ?: "No Label"
                else -> "all"
            }
            groups.getOrPut(groupKey) { mutableListOf() }.add(node)
        }

        for ((groupName, groupNodes) in groups) {
            mermaid.append("  subgraph ${nodeId(groupName)}[\"${escapeLabel(groupName)}\"]\n")
            for (node in groupNodes) mermaid.append("    ${formatNode(node, showState, showPriority)}\n")
            mermaid.append("  end\n")
        }
    } else {
        for (node in nodes) mermaid.append("  ${formatNode(node, showState, showPriority)}\n")
    }

    mermaid.append("\n")
    for (edge in edges) mermaid.append("  ${formatEdge(edge)}\n")

    mermaid.append("\n")
    for (node in nodes) {
        val stateStyle = STATE_STYLES[node.state.lowercase()]
        if (stateStyle != null) mermaid.append("  style ${nodeId(node.identifier)} $stateStyle\n")
    }

    return mermaid.toString()
}

/** Generates a team-dependencies diagram: one node per team, edges labelled with blocking-relation counts. */
fun generateTeamDependencies(nodes: List<IssueNode>, edges: List<IssueEdge>): String {
    val nodeMap = nodes.associateBy { it.identifier }
    val teamEdges = LinkedHashMap<Pair<String, String>, MutableSet<String>>()

    for (edge in edges) {
        if (edge.type != RelationType.BLOCKS) continue
        val fromNode = nodeMap[edge.from] ?: continue
        val toNode = nodeMap[edge.to] ?: continue
        if (fromNode.team == toNode.team) continue

        val key = fromNode.team to toNode.team
        teamEdges.getOrPut(key) { LinkedHashSet() }.add("${edge.from} blocks ${edge.to}")
    }

    val mermaid = StringBuilder("flowchart LR\n")

    val issueCounts = LinkedHashMap<String, Int>()
    for (n in nodes) issueCounts[n.team] = (issueCounts[n.team] ?: 0) + 1
    for ((team, issueCount) in issueCounts) {
        mermaid.append("  ${nodeId(team)}[\"$team<br/><small>$issueCount issues</small>\"]\n")
    }

    mermaid.append("\n")

    for ((key, dependencies) in teamEdges) {
        mermaid.append("  ${nodeId(key.first)} -->|${dependencies.size} blocking| ${nodeId(key.second)}\n")
    }

    return mermaid.toString()
}
