package swim.ui.graph

import androidx.compose.ui.graphics.Color

/** The dark IDE palette the legacy renderer used, ported from its tailwind config. */
internal object Swim {
    val Bg = Color(0xFF1E1E1E)
    val Card = Color(0xFF252526)
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
    val Orange = Color(0xFFF0883E)
    val Todo = Color(0xFF8B949E)
}

/** Sizes and limits the canvas and its caller both need. Canvas units are dp. */
object GraphCanvasDefaults {
    const val NodeWidth: Float = 270f
    const val NodeHeight: Float = 120f
    const val MinScale: Float = 0.1f
    const val MaxScale: Float = 2.0f
}
