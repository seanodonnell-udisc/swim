package swim.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import swim.ui.graph.Swim

/**
 * The dark IDE palette, ported from the legacy tailwind config. The graph canvas already owns
 * the constants, so the app shell reads them from there instead of keeping a second copy.
 */
internal val SwimColors = Swim

/** Chrome sizes the shell and the canvas both measure against. */
object SwimDimens {
    /** Height of the filter toolbar rows, from the legacy 48px header. */
    val HeaderHeight = 48.dp

    /** Corner radius of every control. */
    val Radius = 4.dp

    /** Gap between controls in a toolbar row. */
    val Gap = 8.dp
}

/** The single committed dark look. Desktop v1 has no light theme and no theme switch. */
@Composable
fun SwimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Swim.Accent,
            onPrimary = Swim.Text,
            secondary = Swim.Cyan,
            background = Swim.Bg,
            onBackground = Swim.Text,
            surface = Swim.Card,
            onSurface = Swim.Text,
            surfaceVariant = Swim.CardHover,
            onSurfaceVariant = Swim.TextMuted,
            outline = Swim.Border,
            error = Swim.Red,
            onError = Swim.Text,
        ),
        typography = CompactType,
        content = content,
    )
}

// The legacy font scale runs small: 11sp for chrome, 20sp for the one title.
private val CompactType = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)
