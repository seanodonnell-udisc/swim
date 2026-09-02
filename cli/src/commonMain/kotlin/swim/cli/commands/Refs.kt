package swim.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import swim.cli.Out
import swim.cli.Runtime
import swim.cli.ScopedCommand
import swim.cli.SwimCommand
import swim.cli.bold
import swim.cli.cyan
import swim.cli.gray
import swim.cli.outJson
import swim.cli.repo.analyzeGaps
import swim.cli.repo.findAllReferences
import swim.cli.repo.findOrphanedReferences
import swim.cli.repo.groupReferencesByIssue
import swim.cli.resolveIssueRef
import swim.cli.teamOf
import swim.cli.toScopeJson
import swim.core.analysis.isDone
import swim.core.model.GapAnalysis
import swim.core.model.RepoReference
import swim.core.model.ScopeError

/** The repositories to scan: the flag, or the config. */
private fun repoPaths(flag: List<String>): List<String> {
    val repos = flag.ifEmpty { Runtime.config.repos }
    if (repos.isEmpty()) {
        throw ScopeError("Pass --repo <path> (repeatable) or set \"repos\" in the config file.")
    }
    return repos
}

/** Prints references grouped by the issue they name. */
private fun printRefs(references: List<RepoReference>) {
    for ((issueId, group) in groupReferencesByIssue(references)) {
        Out.line("  ${cyan(issueId)}")
        for (reference in group) Out.line(gray("    ${reference.repo}/${reference.file}:${reference.line}"))
    }
}

/** `swim comment-cleanup` — code comments that name done, unknown, or unevenly-tracked issues. */
class CommentCleanupCommand : ScopedCommand("comment-cleanup") {
    override fun help(context: Context) =
        "Find code comments that reference done, canceled, or unknown issues, and cross-repo gaps"

    private val repo by option("-r", "--repo", metavar = "PATH", help = "git repositories to scan (or set \"repos\" in the config file)")
        .multiple()
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val filters = resolveScope().copy(includeCompleted = true)
        val repos = repoPaths(repo)

        Out.status("Scanning ${repos.size} repositories…")
        val refsByRepo = findAllReferences(repos, Runtime.config)
        Out.status("Fetching issues…")
        val issues = Runtime.linear.getIssueNodes(filters)

        val known = issues.associateBy { it.identifier }
        val teamsInScope = issues.mapTo(mutableSetOf()) { it.team }
        val allRefs = refsByRepo.values.flatten()

        val stale = allRefs.filter { isDone(known[it.issueId]?.stateType) }
        val orphaned = findOrphanedReferences(refsByRepo, known.keys)
            .filter { teamOf(it.issueId) in teamsInScope }
        val gaps = if (repos.size > 1) {
            analyzeGaps(refsByRepo, issues.map { it.identifier to it.title })
        } else {
            emptyList()
        }

        if (json) {
            val scope = buildJsonObject {
                filters.toScopeJson().forEach { (key, value) -> put(key, value) }
                put("repos", buildJsonArray { repos.forEach { add(it) } })
            }
            val data = buildJsonObject {
                put("stale", outJson.encodeToJsonElement(ListSerializer(RepoReference.serializer()), stale))
                put("orphaned", outJson.encodeToJsonElement(ListSerializer(RepoReference.serializer()), orphaned))
                put("gaps", outJson.encodeToJsonElement(ListSerializer(GapAnalysis.serializer()), gaps))
            }
            Out.json("comment-cleanup", scope, data, stale.size + orphaned.size + gaps.size)
            return
        }

        Out.line(bold("References to done or canceled issues (${stale.size})"))
        printRefs(stale)
        Out.line("\n${bold("References to unknown issues (${orphaned.size})")}")
        printRefs(orphaned)
        if (repos.size > 1) {
            Out.line("\n${bold("Issues referenced in some repositories only (${gaps.size})")}")
            for (gap in gaps) {
                Out.line("  ${cyan(gap.identifier)} ${gap.title}")
                Out.line(
                    gray("    in: ${gap.referencedIn.joinToString(", ")}  missing: ${gap.missingIn.joinToString(", ")}")
                )
            }
        }
    }
}

/** `swim refs` — where the code names one issue. */
class RefsCommand : SwimCommand("refs") {
    override fun help(context: Context) = "Where the code references an issue (identifier or URL)"

    private val issue by argument("issue")
    private val repo by option("-r", "--repo", metavar = "PATH", help = "git repositories to scan (or set \"repos\" in the config file)")
        .multiple()
    private val json by option("--json", help = "machine-readable output").flag()

    override suspend fun execute() {
        val identifier = resolveIssueRef(issue)
        val repos = repoPaths(repo)
        Out.status("Scanning ${repos.size} repositories…")
        val references = findAllReferences(repos, Runtime.config).values.flatten()
            .filter { it.issueId == identifier }

        if (json) {
            val scope = buildJsonObject {
                put("issue", identifier)
                put("repos", buildJsonArray { repos.forEach { add(it) } })
            }
            val data = outJson.encodeToJsonElement(ListSerializer(RepoReference.serializer()), references)
            Out.json("refs", scope, data, references.size)
            return
        }
        if (references.isEmpty()) {
            Out.status("No references to $identifier.")
            return
        }
        printRefs(references)
    }
}
