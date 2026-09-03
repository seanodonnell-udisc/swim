package swim.ui.graph

import androidx.compose.ui.graphics.Color

/**
 * The dark IDE palette the legacy renderer used, ported from its tailwind config.
 *
 * The legacy card did NOT paint its outline from this list. `getNodeStyling` reached for
 * Tailwind's own default ramp — `border-2 border-yellow-500`, `ring-2 ring-yellow-500/30` and so
 * on — so the outline, the ring and the badge come from [Card500] and [Card400] below, and only
 * the footer state text uses the hexes here. Both sets are verbatim.
 */
internal object Swim {
    val Bg = Color(0xFF1E1E1E)
    val Card = Color(0xFF252526)

    /** `bg-tertiary`. The legacy issue card filled with this, not with [Card]. */
    val CardHover = Color(0xFF2D2D2D)
    val Border = Color(0xFF3C3C3C)
    val Active = Color(0xFF094771)
    val Text = Color(0xFFCCCCCC)
    val TextMuted = Color(0xFF9D9D9D)
    val Accent = Color(0xFF569CD6)
    val Muted = Color(0xFF6A6A6A)
    val Focus = Color(0xFF007ACC)
    val Cyan = Color(0xFF56B6C2)
    val Purple = Color(0xFFC678DD)
    val Red = Color(0xFFF85149)
    val Amber = Color(0xFFE3B341)
    val Blue = Color(0xFF58A6FF)
    val Green = Color(0xFF3FB950)
    val Orange = Color(0xFFD19A66)
}

/** Tailwind 3's own `-500` ramp, which is what the legacy card outlined itself with. */
internal object Card500 {
    val Yellow = Color(0xFFEAB308)
    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFEF4444)
    val Blue = Color(0xFF3B82F6)
}

/** Tailwind 3's `-400` ramp: the legacy `hover:border-…-400`. */
internal object Card400 {
    val Yellow = Color(0xFFFACC15)
    val Green = Color(0xFF4ADE80)
    val Red = Color(0xFFF87171)
    val Blue = Color(0xFF60A5FA)
}

/**
 * The rotation a grouped area outlines itself with, so two areas side by side never share a
 * colour. Every value is already in [Swim], so the areas add no hex the cards do not use.
 */
private val AREA_COLORS =
    listOf(Swim.Cyan, Swim.Purple, Swim.Amber, Swim.Green, Swim.Blue, Swim.Orange)

/** The outline colour of the area at [index], counting left to right. */
internal fun areaColor(index: Int): Color = AREA_COLORS[index.mod(AREA_COLORS.size)]

/** How thick an area outline is. Thin: it must be noticeable without being in the way. */
internal const val AREA_STROKE: Float = 1.5f

/** Sizes and limits the canvas and its caller both need. Canvas units are dp. */
object GraphCanvasDefaults {
    const val NodeWidth: Float = 270f
    const val NodeHeight: Float = 120f
    const val MinScale: Float = 0.1f
    const val MaxScale: Float = 2.0f
}
