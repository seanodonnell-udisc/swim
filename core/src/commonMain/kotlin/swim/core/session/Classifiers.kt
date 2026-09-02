package swim.core.session

import swim.core.model.PrStatus
import swim.core.model.WorkflowStateType

/** What a workflow state means to the graph. Every surface maps this to its own colors. */
enum class StateCategory { DONE, IN_PROGRESS, IN_REVIEW, BLOCKED, PAUSED, TODO, BACKLOG }

/**
 * Reads a workflow state. Linear lets a workspace name its states freely, so the name decides
 * first, by the substrings the original tool matched on. [stateType] settles the names no
 * substring covers.
 */
fun stateCategory(stateName: String, stateType: WorkflowStateType? = null): StateCategory {
    val name = stateName.lowercase()
    return when {
        DONE_WORDS.any { it in name } -> StateCategory.DONE
        "progress" in name -> StateCategory.IN_PROGRESS
        "review" in name -> StateCategory.IN_REVIEW
        "blocked" in name -> StateCategory.BLOCKED
        "paused" in name -> StateCategory.PAUSED
        "todo" in name -> StateCategory.TODO
        else -> when (stateType) {
            WorkflowStateType.COMPLETED, WorkflowStateType.CANCELED -> StateCategory.DONE
            WorkflowStateType.STARTED -> StateCategory.IN_PROGRESS
            WorkflowStateType.UNSTARTED -> StateCategory.TODO
            WorkflowStateType.BACKLOG, WorkflowStateType.TRIAGE, null -> StateCategory.BACKLOG
        }
    }
}

/** How a reviewer answered. */
enum class ReviewLevel { APPROVED, CHANGES_REQUESTED, NONE }

/** How the checks on the head commit ended. */
enum class CheckLevel { OK, FAILED, PENDING, NONE }

/** One pull-request chip, ready to draw. */
data class PrBadge(
    val number: Int?,
    val review: ReviewLevel,
    val checks: CheckLevel,
    val tooltip: String?,
)

/**
 * Summarizes one pull request for its chip. [title] comes from the Linear attachment; the
 * tooltip joins it with the review and check wording, as GitHub words them.
 */
fun prBadge(url: String, status: PrStatus?, title: String? = null): PrBadge {
    val review = status?.reviewDecision?.let { REVIEWS[it] }
    val checks = status?.checkState?.let { CHECKS[it] }
    val tooltip = listOfNotNull(title, review?.second, checks?.second)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

    return PrBadge(
        number = prNumber(url),
        review = review?.first ?: ReviewLevel.NONE,
        checks = checks?.first ?: CheckLevel.NONE,
        tooltip = tooltip.ifEmpty { null },
    )
}

/** The pull-request number in a GitHub URL. */
fun prNumber(url: String): Int? {
    if (!url.contains("/pull/")) return null
    return url.substringAfter("/pull/").takeWhile { it.isDigit() }.toIntOrNull()
}

private val DONE_WORDS = listOf("done", "completed", "canceled", "cancelled", "invalid")

private val REVIEWS: Map<String, Pair<ReviewLevel, String>> = mapOf(
    "APPROVED" to (ReviewLevel.APPROVED to "Approved"),
    "CHANGES_REQUESTED" to (ReviewLevel.CHANGES_REQUESTED to "Changes requested"),
)

private val CHECKS: Map<String, Pair<CheckLevel, String>> = mapOf(
    "SUCCESS" to (CheckLevel.OK to "Checks passing"),
    "FAILURE" to (CheckLevel.FAILED to "Checks failing"),
    "ERROR" to (CheckLevel.FAILED to "Checks errored"),
    "PENDING" to (CheckLevel.PENDING to "Checks running"),
    "EXPECTED" to (CheckLevel.PENDING to "Checks expected"),
)
