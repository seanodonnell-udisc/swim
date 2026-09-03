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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The idle hint per mode. It names the gestures nothing else on screen advertises. */
private fun idleHint(mode: CanvasMode): String = when (mode) {
    CanvasMode.ARRANGE ->
        "Arrange · Drag a card to move it · Drag a handle to link · Drag empty to select · " +
            "Click a card to act · Scroll to pan · ? shortcuts"
    CanvasMode.INTERACT ->
        "Interact · Click a card for its menu · Click an edge for its panel · " +
            "Scroll to pan · V to arrange · ? shortcuts"
}

/**
 * How long a refusal shakes for, and how far. Short enough that it reads as a rebuff rather than
 * an animation worth watching.
 */
private const val SHAKE_MS = 260f
private const val SHAKE_REACH = 5f

/**
 * The offset a shaking thing sits at this frame: a decaying wobble that settles back at zero.
 * [trigger] rising restarts it, so a second refusal shakes again instead of doing nothing.
 *
 * ponytail: driven off `withFrameMillis`, not an `Animatable`. `animation-core` is not a declared
 * dependency of `:shared` and this is the only animation in it.
 */
@Composable
internal fun shakeOffset(trigger: Int): Float {
    var offset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            val elapsed = withFrameMillis { it } - start
            val fraction = elapsed / SHAKE_MS
            if (fraction >= 1f) break
            offset = sin(fraction * PI.toFloat() * 6f) * SHAKE_REACH * (1f - fraction)
        }
        offset = 0f
    }
    return offset
}

/** The two-icon segmented control. It is the only thing on screen that names the mode. */
@Composable
internal fun ModeToggle(state: GraphCanvasState, modifier: Modifier = Modifier) {
    // The toggle shakes with the card, so a refused drag points at the control that explains it.
    val shakeBy = shakeOffset(state.refusals)
    val refused = shakeBy != 0f
    Row(
        modifier = modifier
            .offset { IntOffset(shakeBy.roundToInt(), 0) }
            .background(Swim.Card, RoundedCornerShape(6.dp))
            .border(1.dp, if (refused) Swim.Amber else Swim.Border, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        // Both glyphs must be plain text. A hand or a pointer renders as a colour emoji on macOS,
        // which is the only bright thing in the chrome and reads as a bug.
        ModeButton("↖", "Arrange (V)", CanvasMode.ARRANGE, state)
        ModeButton("◉", "Interact (I)", CanvasMode.INTERACT, state)
    }
}

/** The one-second note that names the mode just switched into. */
@Composable
internal fun ModeToast(state: GraphCanvasState, modifier: Modifier = Modifier) {
    val toast = state.toast ?: return
    LaunchedEffect(toast.id) {
        delay(1000)
        if (state.toast?.id == toast.id) state.toast = null
    }
    Text(
        text = toast.text,
        color = Swim.Text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(top = 12.dp)
            .background(Swim.Card, RoundedCornerShape(12.dp))
            .border(1.dp, Swim.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
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
            .pointerInput(mode) { detectTapGestures { state.switchTo(mode) } },
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

/**
 * The count on a pile of stacked cards, just off the front card's top right corner, with the
 * members on hover.
 *
 * ponytail: the tooltip is the whole preview. There is no fan-out that spreads the pile out to
 * read every card at once; click a peeking card to bring it forward instead.
 */
@Composable
internal fun StackBadge(members: Set<String>, front: Rect, density: Float) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    ((front.right + 4f) * density).roundToInt(),
                    (front.top * density).roundToInt(),
                )
            }
            .wrapContentSize(unbounded = true, align = Alignment.TopStart),
    ) {
        Text(
            text = "×${members.size}",
            color = Swim.Accent,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier
                .background(Swim.Card, RoundedCornerShape(3.dp))
                .border(1.dp, Swim.Border, RoundedCornerShape(3.dp))
                .hoverable(interaction)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (hovered) {
            Text(
                text = "One PR branch: ${members.sorted().joinToString(", ")}",
                color = Swim.Text,
                fontSize = 10.sp,
                maxLines = 3,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = 20.dp)
                    .wrapContentSize(unbounded = true, align = Alignment.TopStart)
                    .widthIn(max = 220.dp)
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
    "V, or the ↖ button" to "Arrange: move cards, draw relations, select",
    "I, or the ◉ button" to "Interact: act on a card or an edge",
)

private val GESTURES = listOf(
    "Two-finger scroll" to "Pan both axes, in either mode",
    "⌘ or ctrl with scroll" to "Zoom at the pointer",
    "Left drag, in Arrange" to "Move a card, or draw a selection box",
    "Drag a card handle, in Arrange" to "Draw a relation",
    "Left drag, in Interact" to "Refused: the card shakes and stays put",
    "Click a card" to "Its menu, switching to Interact first",
    "Click an edge" to "Its panel, switching to Interact first",
    "⇧ or ⌘ click" to "Toggle the card in the selection",
    "Double click empty canvas" to "Zoom to fit",
    "Right click" to "Menu for the card, the edge, or the canvas",
)

private val KEYS = listOf(
    "V / I" to "Arrange, Interact",
    "?" to "This help",
    "Esc" to "Clear the selection, close menus",
    "+ / −" to "Zoom in, zoom out",
    "0 or ⌘0" to "Zoom to fit",
    "⌘R" to "Reload",
    "⌘L" to "Re-layout",
    "⌘\\" to "Fold the side panel away, or back",
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
