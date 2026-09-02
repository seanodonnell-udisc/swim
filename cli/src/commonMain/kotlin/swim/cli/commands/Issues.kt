@file:OptIn(ExperimentalTime::class)

package swim.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
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
import swim.cli.formatAge
import swim.cli.formatIssueLine
import swim.cli.gray
import swim.cli.outJson
import swim.cli.resolveIssueRef
import swim.cli.toScopeJson
import swim.cli.truncate
import swim.cli.yellow
import swim.core.analysis.GroupBy
import swim.core.analysis.groupIssues
import swim.core.model.IssueNode
import swim.core.model.NotFoundError
import swim.core.model.PRIORITY_LABELS
import swim.core.model.ScopeError
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val SORTS = listOf("priority", "updated", "created")
private val GROUP_BYS = listOf("state", "priority", "assignee", "team", "project")

/** `swim list` — the issues in scope, sorted or grouped. */
class ListCommand : ScopedCommand("list") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "List issues in scope"

    private val sort by option("--sort", metavar = "FIELD", help = "priority | updated | created (oldest first)")
        .default("priority")
    private val groupBy by option("--group-by", metavar = "FIELD", help = "state | priority | assignee | team | project")
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val filters = resolveScope()
        if (sort !in SORTS) throw ScopeError("--sort must be one of ${SORTS.joinToString(", ")}")
        groupBy?.let {
            if (it !in GROUP_BYS) throw ScopeError("--group-by must be one of ${GROUP_BYS.joinToString(", ")}")
        }

        Out.status("Fetching issues…")
        val issues = Runtime.linear.getIssueNodes(filters).sortedWith(comparatorFor(sort))
        val groups = groupBy?.let { groupIssues(issues, GroupBy.valueOf(it.uppercase())) }

        if (json) {
            val data = if (groups != null) {
                outJson.encodeToJsonElement(
                    MapSerializer(String.serializer(), ListSerializer(IssueNode.serializer())),
                    groups,
                )
            } else {
                outJson.encodeToJsonElement(ListSerializer(IssueNode.serializer()), issues)
            }
            Out.json("list", filters.toScopeJson(), data, issues.size)
            return
        }

        if (issues.isEmpty()) {
            Out.status("No issues match.")
            return
        }
        Out.status("${issues.size} issues")
        if (groups != null) {
            for ((name, group) in groups.entries.sortedByDescending { it.value.size }) {
                Out.line(bold("\n$name (${group.size})"))
                for (issue in group) Out.line("  " + formatIssueLine(issue))
            }
        } else {
            for (issue in issues) Out.line("${formatIssueLine(issue)} ${gray(formatAge(issue.updatedAt))}")
        }
    }

    private fun comparatorFor(field: String): Comparator<IssueNode> = when (field) {
        "updated" -> compareBy { it.updatedAt.epochOrZero() }
        "created" -> compareBy { it.createdAt.epochOrZero() }
        else -> compareBy { if (it.priority == 0) 5 else it.priority }
    }

    private fun Instant?.epochOrZero(): Long = this?.toEpochMilliseconds() ?: 0L
}

/** `swim show` — one issue with its relations and pull requests. */
class ShowCommand : SwimCommand("show") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Show one issue with its relations and pull requests (identifier or URL)"

    private val issue by argument("issue")
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val identifier = resolveIssueRef(issue)
        Out.status("Fetching $identifier…")
        val detail = Runtime.linear.getIssueDetail(identifier)
            ?: throw NotFoundError("Issue not found: $identifier")
        val node = detail.node

        if (json) {
            val data = buildJsonObject {
                outJson.encodeToJsonElement(IssueNode.serializer(), node).jsonObject
                    .forEach { (key, value) -> put(key, value) }
                put("priorityLabel", PRIORITY_LABELS[node.priority])
                put(
                    "relations",
                    outJson.encodeToJsonElement(
                        ListSerializer(swim.core.model.IssueRelationDetail.serializer()),
                        detail.relations,
                    ),
                )
            }
            Out.json("show", buildJsonObject { put("issue", identifier) }, data)
            return
        }

        Out.line(cyan(bold("${node.identifier}: ${node.title}")))
        Out.line("${bold("State:")}    ${node.state}")
        Out.line("${bold("Priority:")} ${PRIORITY_LABELS[node.priority]}")
        Out.line("${bold("Team:")}     ${node.team}")
        Out.line("${bold("Project:")}  ${node.project ?: "None"}")
        Out.line("${bold("Assignee:")} ${node.assignee ?: "Unassigned"}")
        Out.line("${bold("Labels:")}   ${node.labels.joinToString(", ").ifEmpty { "None" }}")
        node.estimate?.let { Out.line("${bold("Estimate:")} $it") }
        Out.line("${bold("URL:")}      ${node.url}")
        for (pr in node.pullRequests.orEmpty()) Out.line("${bold("PR:")}       ${pr.url}  ${gray(pr.title)}")

        node.description?.takeIf { it.isNotEmpty() }?.let { description ->
            Out.line("\n${bold("Description:")}")
            Out.line(
                if (description.length > DESCRIPTION_LIMIT) {
                    description.substring(0, DESCRIPTION_LIMIT) + gray("\n… (truncated)")
                } else {
                    description
                }
            )
        }

        if (detail.relations.isNotEmpty()) {
            Out.line("\n${bold("Relations:")}")
            for (relation in detail.relations) {
                Out.line(
                    "  ${yellow(relation.type.padEnd(10))} ${cyan(relation.identifier)} " +
                        "${truncate(relation.title, 60)} ${gray("[${relation.state}]")}"
                )
            }
        }
    }
}

/** `swim teams` — every team. */
class TeamsCommand : SwimCommand("teams") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "List teams"

    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val teams = Runtime.linear.getTeams()
        if (json) {
            val data = buildJsonArray {
                for (team in teams) {
                    add(buildJsonObject { put("id", team.id); put("key", team.key); put("name", team.name) })
                }
            }
            Out.json("teams", JsonObject(emptyMap()), data, teams.size)
            return
        }
        for (team in teams) Out.line("${cyan(team.key.padEnd(6))} ${team.name}")
    }
}

/** `swim projects` — every project, or one team's projects. */
class ProjectsCommand : SwimCommand("projects") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "List projects"

    private val team by option("-t", "--team", metavar = "KEY", help = "only projects of this team")
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val projects = Runtime.linear.getProjects(teamIdFor(team))
        if (json) {
            val data = buildJsonArray {
                for (project in projects) {
                    add(
                        buildJsonObject {
                            put("id", project.id); put("name", project.name); put("state", project.state)
                        }
                    )
                }
            }
            Out.json("projects", buildJsonObject { team?.let { put("team", it) } }, data, projects.size)
            return
        }
        for (project in projects) Out.line("${cyan(project.name)}  ${gray(project.state)}")
    }
}

/** `swim labels` — every label, or one team's labels. */
class LabelsCommand : SwimCommand("labels") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "List labels"

    private val team by option("-t", "--team", metavar = "KEY", help = "only labels of this team")
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val labels = Runtime.linear.getLabels(teamIdFor(team))
        if (json) {
            val data = buildJsonArray {
                for (label in labels) {
                    add(
                        buildJsonObject {
                            put("id", label.id); put("name", label.name); put("color", label.color)
                        }
                    )
                }
            }
            Out.json("labels", buildJsonObject { team?.let { put("team", it) } }, data, labels.size)
            return
        }
        for (label in labels) Out.line(label.name)
    }
}

/** The id of a team named by key or name. */
private suspend fun teamIdFor(key: String?): String? {
    if (key == null) return null
    val team = Runtime.linear.getTeamByName(key)
        ?: throw ScopeError("Unknown team: $key. Run `swim teams` to list teams.")
    return team.id
}

private const val DESCRIPTION_LIMIT = 800
