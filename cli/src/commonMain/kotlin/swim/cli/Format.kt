@file:OptIn(ExperimentalTime::class)

package swim.cli

import swim.core.model.IssueNode
import swim.core.model.WorkflowStateType
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The age of an issue, rounded to the largest unit that stays readable. */
fun formatAge(moment: Instant?): String {
    if (moment == null) return "?"
    val days = (Clock.System.now() - moment).inWholeDays
    return when {
        days < 1 -> "<1d"
        days < 30 -> "${days}d"
        days < 365 -> "${days / 30}mo"
        else -> {
            val tenths = (days / 365.0 * 10).roundToInt()
            "${tenths / 10}.${tenths % 10}y"
        }
    }
}

/** One issue on one line: identifier, priority dot, title, state, assignee. */
fun formatIssueLine(issue: IssueNode): String {
    val assignee = issue.assignee?.let { " " + gray("→ $it") } ?: ""
    val dot = priorityColor(issue.priority)("●")
    return "${cyan(issue.identifier.padEnd(9))} $dot ${truncate(issue.title, 70)}  " +
        "${gray("[${issue.state}]")}$assignee"
}

/** A filled bar `width` characters wide. */
fun progressBar(percent: Int, width: Int): String {
    val filled = (percent / 100.0 * width).roundToInt()
    return green("█".repeat(filled)) + gray("░".repeat(width - filled))
}

/** The wire name of a state type, for example `completed`. */
fun WorkflowStateType.wireName(): String = name.lowercase()
