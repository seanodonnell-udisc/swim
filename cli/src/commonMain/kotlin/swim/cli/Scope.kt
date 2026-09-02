package swim.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import swim.core.model.FilterOptions
import swim.core.model.ScopeError
import swim.core.url.isLinearUrl
import swim.core.url.resolveLinearUrl

/**
 * The scope options that every command reading a set of issues shares. A scope is mandatory:
 * a bare command never reads the whole workspace.
 */
abstract class ScopedCommand(name: String) : SwimCommand(name) {
    private val url by argument("url", help = "Linear URL of a team, project, cycle, or filtered view; sets the scope")
        .optional()
    private val team by option("-t", "--team", metavar = "KEYS", help = "team key(s), comma-separated (e.g. ENG,WEB)")
    private val project by option("-p", "--project", metavar = "NAME", help = "project name")
    private val label by option("-l", "--label", metavar = "NAME", help = "label name")
    private val excludeLabel by option("--exclude-label", metavar = "NAME", help = "drop issues carrying this label")
    private val priority by option("--priority", metavar = "N", help = "0 (none) to 4 (low)")
    private val state by option("--state", metavar = "NAME", help = "workflow state name, substring match")
    private val stateType by option("--state-type", metavar = "TYPES", help = "backlog,unstarted,started,completed,canceled")
    private val assignee by option("--assignee", metavar = "NAME", help = "assignee name, substring match")
    private val cycle by option("--cycle", metavar = "ID", help = "cycle id")
    private val includeCompleted by option("--include-completed", help = "include completed and canceled issues").flag()

    /** Applies the URL first, then the flags, then checks that something narrows the workspace. */
    protected suspend fun resolveScope(): FilterOptions {
        var filters = FilterOptions()

        url?.let { text ->
            if (!isLinearUrl(text)) throw ScopeError("Not a Linear URL: $text")
            filters = resolveLinearUrl(text, Runtime.linear).filters
        }

        team?.let { filters = filters.copy(team = it) }
        project?.let { filters = filters.copy(project = it, projectId = null) }
        label?.let { filters = filters.copy(label = it) }
        excludeLabel?.let { filters = filters.copy(excludeLabel = it) }
        state?.let { filters = filters.copy(state = it) }
        stateType?.let { filters = filters.copy(stateType = it) }
        assignee?.let { filters = filters.copy(assignee = it) }
        cycle?.let { filters = filters.copy(cycleId = it) }
        if (includeCompleted) filters = filters.copy(includeCompleted = true)
        priority?.let { text ->
            val value = text.toIntOrNull()
            if (value == null || value !in 0..4) throw ScopeError("--priority must be 0-4, got $text")
            filters = filters.copy(priority = value)
        }

        if (filters.team == null && filters.project == null && filters.label == null &&
            filters.cycleId == null && filters.assignee == null
        ) {
            throw ScopeError(
                "A scope is required: pass a Linear URL, or one of --team, --project, --label, --cycle, --assignee."
            )
        }
        return filters
    }
}

/** The scope object of the `--json` payload. */
fun FilterOptions.toScopeJson(): JsonObject =
    outJson.encodeToJsonElement(FilterOptions.serializer(), this).jsonObject.withoutDefaults()

/** Accepts an identifier or an issue URL and returns the upper-case identifier. */
suspend fun resolveIssueRef(ref: String): String {
    if (isLinearUrl(ref)) {
        val resolved = resolveLinearUrl(ref, Runtime.linear)
        return resolved.singleIssueId?.uppercase() ?: throw ScopeError("Not an issue URL: $ref")
    }
    val identifier = ref.uppercase()
    if (!IDENTIFIER.matches(identifier)) {
        throw ScopeError("Not an issue identifier: $ref (expected e.g. ENG-123)")
    }
    return identifier
}

/** The team key of an identifier. */
fun teamOf(identifier: String): String = identifier.substringBefore("-")

/** Reads a count flag that must be one or more. */
fun parsePositiveInt(value: String?, name: String, fallback: Int): Int {
    if (value == null) return fallback
    val parsed = value.toIntOrNull()
    if (parsed == null || parsed < 1) throw ScopeError("$name must be a positive integer, got $value")
    return parsed
}

private val IDENTIFIER = Regex("^[A-Z0-9]+-\\d+$")
