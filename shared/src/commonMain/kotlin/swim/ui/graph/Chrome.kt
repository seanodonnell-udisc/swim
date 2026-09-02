package swim.ui.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The idle hint per mode. It names the gestures nothing else on screen advertises. */
private fun idleHint(mode: CanvasMode): String = when (mode) {
    CanvasMode.ARRANGE ->
        "Arrange · Drag a card to move it · Drag empty to select · H to pan and link · ? shortcuts"
    CanvasMode.INTERACT ->
        "Pan and link · Drag anywhere to pan · Drag a card handle to link · V to arrange · ? shortcuts"
}

/** The two-icon segmented control. It is the only thing on screen that names the mode. */
@Composable
internal fun ModeToggle(state: GraphCanvasState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Swim.Card, RoundedCornerShape(6.dp))
            .border(1.dp, Swim.Border, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        ModeButton("↖", "Arrange (V)", CanvasMode.ARRANGE, state)
        ModeButton("✥", "Pan and link (H)", CanvasMode.INTERACT, state)
    }
}

@Composable
private fun ModeButton(
    glyph: String,
    label: String,
    mode: CanvasMode,
    state: GraphCanvasState,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = state.mode == mode
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .background(
                if (selected) Swim.Focus else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(mode) { detectTapGestures { state.mode = mode } },
    ) {
        Text(glyph, color = if (selected) Swim.Text else Swim.TextMuted, fontSize = 14.sp)
        if (hovered && !selected) {
            Text(
                text = label,
                color = Swim.Text,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = 28.dp)
                    .wrapContentSize(unbounded = true, align = Alignment.TopStart)
                    .background(Swim.Bg, RoundedCornerShape(4.dp))
                    .border(1.dp, Swim.Border, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

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
        else -> idleHint(state.mode)
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

private val MODES = listOf(
    "V, or the ↖ button" to "Arrange: drag cards, drag empty to select",
    "H, or the ✥ button" to "Pan and link: drag to pan, handles on hover",
)

private val GESTURES = listOf(
    "Left drag, in Arrange" to "Move a card, or draw a selection box",
    "Left drag, in Pan and link" to "Pan, over a card as well",
    "Right or middle drag" to "Pan, in either mode",
    "Space with left drag" to "Pan, in either mode",
    "Two-finger scroll" to "Pan both axes",
    "⌘ or ctrl with scroll" to "Zoom at the pointer",
    "Double click empty canvas" to "Zoom to fit",
    "Click, ⇧ or ⌘ click" to "Select, toggle in the selection",
    "Drag a card handle" to "Draw a relation, in Pan and link",
    "Right click" to "Menu for the card, the edge, or the canvas",
)

private val KEYS = listOf(
    "V / H" to "Arrange, Pan and link",
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
            Section("Modes", MODES)
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
