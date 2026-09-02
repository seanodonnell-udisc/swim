package swim.ui.graph

import androidx.compose.ui.graphics.Color
import swim.core.model.PrStatus
import swim.core.model.WorkflowStateType

/** How a card looks. The legacy renderer derived this from the state NAME, not the state type. */
internal enum class CardCategory { DONE, IN_PROGRESS, IN_REVIEW, BLOCKED, PAUSED, TODO, DEFAULT }

// ponytail: a copy of the legacy substring rules. Swap the body for
// swim.core.session.stateCategory when that classifier lands.
internal fun cardCategory(state: String, stateType: WorkflowStateType): CardCategory {
    val name = state.lowercase()
    return when {
        stateType == WorkflowStateType.COMPLETED || stateType == WorkflowStateType.CANCELED ->
            CardCategory.DONE
        name.contains("done") || name.contains("complete") || name.contains("cancel") ||
            name.contains("invalid") -> CardCategory.DONE
        name.contains("progress") -> CardCategory.IN_PROGRESS
        name.contains("review") -> CardCategory.IN_REVIEW
        name.contains("blocked") -> CardCategory.BLOCKED
        name.contains("paused") -> CardCategory.PAUSED
        name.contains("todo") -> CardCategory.TODO
        else -> CardCategory.DEFAULT
    }
}

/** The badge a category earns, or null when the card shows none. */
internal fun categoryBadge(category: CardCategory, ready: Boolean): String? = when (category) {
    CardCategory.IN_PROGRESS -> "In Progress"
    CardCategory.IN_REVIEW -> "In Review"
    CardCategory.BLOCKED -> "Blocked"
    CardCategory.PAUSED -> "Paused"
    CardCategory.TODO -> if (ready) "Ready" else null
    else -> null
}

internal fun categoryColor(category: CardCategory): Color = when (category) {
    CardCategory.DONE -> Swim.Green
    CardCategory.IN_PROGRESS -> Swim.Orange
    CardCategory.IN_REVIEW -> Swim.Green
    CardCategory.BLOCKED -> Swim.Red
    CardCategory.PAUSED -> Swim.Blue
    CardCategory.TODO -> Swim.Todo
    CardCategory.DEFAULT -> Swim.Muted
}

internal fun categoryBorderWidth(category: CardCategory): Float = when (category) {
    CardCategory.IN_PROGRESS, CardCategory.IN_REVIEW, CardCategory.BLOCKED, CardCategory.PAUSED -> 2f
    else -> 1f
}

internal fun categoryBorderColor(category: CardCategory): Color = when (category) {
    CardCategory.TODO -> Swim.Text
    CardCategory.DONE, CardCategory.DEFAULT -> Swim.Border
    else -> categoryColor(category)
}

/** Available work pops: everything else in the two neutral categories is held back. */
internal fun cardAlpha(category: CardCategory, ready: Boolean): Float = when {
    category == CardCategory.DONE -> 0.4f
    (category == CardCategory.TODO || category == CardCategory.DEFAULT) && !ready -> 0.5f
    else -> 1f
}

internal fun priorityColor(priority: Int): Color = when (priority) {
    1 -> Swim.Red
    2 -> Swim.Amber
    3 -> Swim.Blue
    4 -> Swim.Green
    else -> Swim.Muted
}

/** Minimap dot colors. Membership of the ready set outranks priority. */
internal fun minimapColor(ready: Boolean, priority: Int): Color =
    if (ready) Color.White else priorityColor(priority)

/** One PR chip, already reduced to what the chip draws. */
internal data class PrChip(
    val url: String,
    val label: String,
    val tooltip: String,
    val reviewMark: String?,
    val reviewColor: Color,
    val checkColor: Color?,
)

private val PR_NUMBER = Regex("""/pull/(\d+)""")

/** The PR number in a GitHub pull-request URL. */
internal fun prNumber(url: String): String? = PR_NUMBER.find(url)?.groupValues?.get(1)

internal fun prChip(url: String, title: String, status: PrStatus?): PrChip {
    val number = prNumber(url)
    val review = when (status?.reviewDecision?.uppercase()) {
        "APPROVED" -> Triple("✓", Swim.Green, "Approved")
        "CHANGES_REQUESTED" -> Triple("±", Swim.Red, "Changes requested")
        "REVIEW_REQUIRED" -> Triple(null, Swim.Muted, "Review required")
        else -> Triple(null, Swim.Muted, null)
    }
    val checks = when (status?.checkState?.uppercase()) {
        "SUCCESS" -> Swim.Green to "Checks passed"
        "FAILURE", "ERROR" -> Swim.Red to "Checks failed"
        "PENDING", "EXPECTED" -> Swim.Amber to "Checks running"
        else -> null to null
    }
    val parts = listOfNotNull(title.takeIf { it.isNotBlank() }, review.third, checks.second)
    return PrChip(
        url = url,
        label = if (number == null) "PR" else "PR #$number",
        tooltip = parts.joinToString(" · "),
        reviewMark = review.first,
        reviewColor = review.second,
        checkColor = checks.first,
    )
}
