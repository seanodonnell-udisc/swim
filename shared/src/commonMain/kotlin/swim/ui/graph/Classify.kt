package swim.ui.graph

import androidx.compose.ui.graphics.Color
import swim.core.github.parsePrUrl
import swim.core.model.PrStatus
import swim.core.model.WorkflowStateType
import swim.core.session.StateCategory
import swim.core.session.stateCategory

/** How a card looks. The legacy renderer derived this from the state NAME, not the state type. */
internal enum class CardCategory { DONE, IN_PROGRESS, IN_REVIEW, BLOCKED, PAUSED, TODO, DEFAULT }

/** The one classifier in `swim.core.session`, mapped onto the categories the card draws. */
internal fun cardCategory(state: String, stateType: WorkflowStateType): CardCategory =
    when (stateCategory(state, stateType)) {
        StateCategory.DONE -> CardCategory.DONE
        StateCategory.IN_PROGRESS -> CardCategory.IN_PROGRESS
        StateCategory.IN_REVIEW -> CardCategory.IN_REVIEW
        StateCategory.BLOCKED -> CardCategory.BLOCKED
        StateCategory.PAUSED -> CardCategory.PAUSED
        StateCategory.TODO -> CardCategory.TODO
        StateCategory.BACKLOG -> CardCategory.DEFAULT
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

/**
 * How one card is outlined and tinted. This is the legacy `getNodeStyling` table, ported class
 * for class: `border-2 border-{c}-500` is [border], `ring-2 ring-{c}-500/30` is [ring],
 * `hover:border-{c}-400` is [hoverBorder], and the header's `border-b {c}/30` and `bg-{c}/10`
 * are [divider] and [headerTint].
 */
internal data class CardStyle(
    val border: Color,
    val borderWidth: Float,
    val hoverBorder: Color,
    /** Null for the two categories the legacy card gave no ring: done and backlog. */
    val ring: Color?,
    val headerTint: Color,
    val divider: Color,
    val badge: Color,
    val badgeText: Color,
)

/** The legacy ringed card: a solid outline, a 30% halo outside it, a 10% wash behind the header. */
private fun ringed(
    accent: Color,
    hover: Color,
    badgeText: Color = Color.White,
    borderAlpha: Float = 1f,
    ringAlpha: Float = 0.3f,
    tintAlpha: Float = 0.10f,
    badgeAlpha: Float = 1f,
) = CardStyle(
    border = accent.copy(alpha = borderAlpha),
    borderWidth = 2f,
    hoverBorder = hover,
    ring = accent.copy(alpha = ringAlpha),
    headerTint = accent.copy(alpha = tintAlpha),
    divider = accent.copy(alpha = 0.3f),
    badge = accent.copy(alpha = badgeAlpha),
    badgeText = badgeText,
)

/** The legacy quiet card: one hairline in the default border color and nothing else. */
private fun quiet(hover: Color) = CardStyle(
    border = Swim.Border,
    borderWidth = 1f,
    hoverBorder = hover,
    ring = null,
    headerTint = Color.Transparent,
    divider = Swim.Border,
    badge = Swim.Border,
    badgeText = Swim.Text,
)

internal fun cardStyle(category: CardCategory): CardStyle = when (category) {
    CardCategory.IN_PROGRESS ->
        ringed(Card500.Yellow, Card400.Yellow, badgeText = Color.Black)
    CardCategory.IN_REVIEW -> ringed(Card500.Green, Card400.Green)
    CardCategory.BLOCKED -> ringed(Card500.Red, Card400.Red)
    CardCategory.PAUSED -> ringed(Card500.Blue, Card400.Blue)
    // The one category the legacy card drew in plain white, at four different alphas.
    CardCategory.TODO -> ringed(
        accent = Color.White,
        hover = Color.White,
        badgeText = Color.Black,
        borderAlpha = 0.8f,
        ringAlpha = 0.2f,
        tintAlpha = 0.05f,
        badgeAlpha = 0.9f,
    )
    CardCategory.DONE -> quiet(Swim.Border)
    CardCategory.DEFAULT -> quiet(Swim.Focus)
}

/**
 * The footer state text. The legacy renderer read this off its OWN palette, not off the Tailwind
 * ramp the outline used, so an in-review card outlines in `#22C55E` and writes its state in
 * `#3FB950`. A canceled or invalid state groups with done everywhere but here, where it greys out.
 */
internal fun stateColor(state: String, category: CardCategory): Color {
    val name = state.lowercase()
    if (CANCELED_WORDS.any { it in name }) return Swim.Muted
    return when (category) {
        CardCategory.DONE, CardCategory.IN_REVIEW -> Swim.Green
        CardCategory.IN_PROGRESS -> Card500.Yellow
        CardCategory.BLOCKED -> Swim.Red
        CardCategory.PAUSED -> Swim.Blue
        CardCategory.TODO -> Swim.TextMuted
        CardCategory.DEFAULT -> Swim.Muted
    }
}

private val CANCELED_WORDS = listOf("cancel", "invalid")

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

internal fun prChip(url: String, title: String, status: PrStatus?): PrChip {
    val number = parsePrUrl(url)?.number
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
