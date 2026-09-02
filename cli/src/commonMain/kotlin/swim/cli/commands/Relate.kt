package swim.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.CancellationException
import swim.cli.Exit
import swim.cli.Out
import swim.cli.Runtime
import swim.cli.SwimCommand
import swim.cli.cyan
import swim.cli.gray
import swim.cli.green
import swim.cli.red
import swim.cli.resolveIssueRef
import swim.cli.yellow
import swim.core.model.NotFoundError
import swim.core.model.RelationType
import swim.core.model.ScopeError
import platform.posix.exit as posixExit

private val RELATION_TYPES = listOf("blocks", "related", "duplicate")

/** Reads a relation type, or reports the three that exist. */
private fun parseRelationType(type: String): RelationType {
    val lowercase = type.lowercase()
    if (lowercase !in RELATION_TYPES) {
        throw ScopeError("Relation type must be one of ${RELATION_TYPES.joinToString(", ")}, got $type")
    }
    return RelationType.valueOf(lowercase.uppercase())
}

/** Reads one issue, or reports that it does not exist. */
private suspend fun requireIssue(identifier: String) =
    Runtime.linear.getIssueDetail(identifier)?.node ?: throw NotFoundError("Issue not found: $identifier")

/** `swim relate` — `relate A blocks B` creates the relation A to B. */
class RelateCommand : SwimCommand("relate") {
    override fun help(context: Context) =
        "Create a relation: `relate ENG-1 blocks ENG-2` (types: blocks, related, duplicate)"

    private val from by argument("from")
    private val type by argument("type")
    private val to by argument("to")

    override suspend fun execute() {
        val relationType = parseRelationType(type)
        val fromId = resolveIssueRef(from)
        val toId = resolveIssueRef(to)
        val fromIssue = requireIssue(fromId)
        val toIssue = requireIssue(toId)

        Out.status("Creating $fromId ${relationType.wire()} $toId…")
        Runtime.linear.createIssueRelation(fromId, toId, relationType)
        Out.line("${cyan(fromIssue.identifier)} ${yellow(relationType.wire())} ${cyan(toIssue.identifier)}")
        Out.line(gray("  \"${fromIssue.title}\" → \"${toIssue.title}\""))
    }
}

/** `swim bulk-relate` — the same relation from many issues to one. */
class BulkRelateCommand : SwimCommand("bulk-relate") {
    override fun help(context: Context) =
        "Create the same relation from many issues to one: `bulk-relate blocks --to ENG-9 ENG-1 ENG-2`"

    private val type by argument("type")
    private val from by argument("from").multiple(required = true)
    private val to by option("--to", metavar = "ISSUE", help = "target issue").required()

    override suspend fun execute() {
        val relationType = parseRelationType(type)
        val toId = resolveIssueRef(to)
        val toIssue = requireIssue(toId)

        var created = 0
        var failed = 0
        for (reference in from) {
            val fromId = resolveIssueRef(reference)
            try {
                requireIssue(fromId)
                Runtime.linear.createIssueRelation(fromId, toId, relationType)
                Out.line("${green("✓")} ${cyan(fromId)} ${relationType.wire()} ${cyan(toIssue.identifier)}")
                created++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Out.line("${red("✗")} $fromId: ${e.message ?: e.toString()}")
                failed++
            }
        }
        Out.status("$created created, $failed failed")
        if (failed > 0) posixExit(Exit.ERROR)
    }
}

/** The wire name Linear uses for a relation type. */
private fun RelationType.wire(): String = name.lowercase()
