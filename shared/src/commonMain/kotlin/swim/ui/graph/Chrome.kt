package swim.ui.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The default hint. It names the two gestures nothing else on screen advertises. */
private const val IDLE_HINT =
    "Drag a handle to link issues · Right-click for actions · ⌘0 fit · ? for shortcuts"

/** The slim bar along the bottom of the canvas: what to do on the left, the zoom on the right. */
@Composable
internal fun HintBar(
    state: GraphCanvasState,
    selectionCount: Int,
    modifier: Modifier = Modifier,
) {
    val hint = when {
        state.pick != null -> "Click the target issue · Esc cancels"
        selectionCount > 0 -> "$selectionCount selected · Esc to clear"
        else -> IDLE_HINT
    }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Swim.Border))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(23.dp)
                .background(Swim.Card)
                .padding(horizontal = 10.dp),
        ) {
            Text(hint, color = Swim.TextMuted, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                text = "?",
                color = Swim.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) {
                        detectTapGestures { state.shortcutsVisible = !state.shortcutsVisible }
                    }
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = "${(state.scale * 100f).roundToInt()}%",
                color = Swim.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) { detectTapGestures { state.resetZoom() } }
                    .width(44.dp),
            )
        }
    }
}

/** The floating note that says pick mode is on, over the middle of the canvas top. */
@Composable
internal fun PickHint(modifier: Modifier = Modifier) {
    Text(
        text = "Click the target issue · Esc cancels",
        color = Swim.Text,
        fontSize = 11.sp,
        modifier = modifier
            .padding(top = 12.dp)
            .background(Swim.Card, RoundedCornerShape(12.dp))
            .border(1.dp, Swim.Focus, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

private val GESTURES = listOf(
    "Left drag on empty canvas" to "Selection box",
    "Right or middle drag" to "Pan",
    "Space with left drag" to "Pan",
    "Two-finger scroll" to "Pan both axes",
    "⌘ or ctrl with scroll" to "Zoom at the pointer",
    "Double click empty canvas" to "Zoom to fit",
    "Click, ⇧ or ⌘ click" to "Select, add to the selection",
    "Drag a card" to "Move it, and the selection with it",
    "Drag a card handle" to "Draw a relation to another card",
    "Right click" to "Menu for the card, the edge, or the canvas",
)

private val KEYS = listOf(
    "?" to "This help",
    "Esc" to "Clear the selection, close menus",
    "+ / −" to "Zoom in, zoom out",
    "0 or ⌘0" to "Zoom to fit",
    "⌘R" to "Reload",
    "⌘L" to "Re-layout",
)

/** The gestures-and-shortcuts card. Any click or Esc closes it. */
@Composable
internal fun ShortcutsOverlay(onDismiss: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Swim.Bg.copy(alpha = 0.88f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .width(520.dp)
                .background(Swim.Card, RoundedCornerShape(8.dp))
                .border(1.dp, Swim.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Gestures and shortcuts", color = Swim.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Section("Pointer", GESTURES)
            Section("Keys", KEYS)
            Text("Press Esc or click to close.", color = Swim.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Swim.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        rows.forEach { (input, result) ->
            Row(Modifier.fillMaxWidth()) {
                Text(input, color = Swim.Text, fontSize = 11.sp, modifier = Modifier.width(200.dp))
                Text(result, color = Swim.TextMuted, fontSize = 11.sp)
            }
        }
    }
}
