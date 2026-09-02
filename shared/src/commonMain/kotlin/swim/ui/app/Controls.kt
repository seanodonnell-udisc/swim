package swim.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import swim.ui.graph.Swim
import swim.ui.theme.SwimDimens

/** One option in a select. [value] is what the filter stores; [label] is what the user reads. */
internal data class SwimOption(val value: String, val label: String)

private val ControlShape = RoundedCornerShape(SwimDimens.Radius)
private val ControlHeight = 28.dp

/** A compact filled button. The primary form carries the accent; the rest are quiet. */
@Composable
internal fun SwimButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        !enabled -> Swim.Card
        primary && hovered -> Swim.Focus
        primary -> Swim.Active
        hovered -> Swim.Border
        else -> Swim.CardHover
    }
    Box(
        modifier = modifier
            .heightIn(min = ControlHeight)
            .background(background, ControlShape)
            .border(1.dp, if (primary && enabled) Swim.Focus else Swim.Border, ControlShape)
            .hoverable(interaction, enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Swim.Text else Swim.Muted,
            fontSize = 11.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** A control that opens a menu. The label reads `Name` when nothing is set, `Name: value` when it is. */
@Composable
private fun MenuField(
    label: String,
    value: String?,
    width: Dp,
    modifier: Modifier = Modifier,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .width(width)
                .heightIn(min = ControlHeight)
                .background(if (hovered) Swim.CardHover else Swim.Card, ControlShape)
                .border(1.dp, if (value == null) Swim.Border else Swim.Focus, ControlShape)
                .hoverable(interaction)
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (value == null) label else "$label: $value",
                color = if (value == null) Swim.TextMuted else Swim.Text,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = Swim.Muted, fontSize = 9.sp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Swim.Card),
        ) {
            menu { open = false }
        }
    }
}

/** One row inside a menu. */
@Composable
private fun MenuRow(text: String, checked: Boolean?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) Swim.CardHover else Color.Transparent)
            .hoverable(interaction)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Text(
                text = if (checked) "☑" else "☐",
                color = if (checked) Swim.Accent else Swim.Muted,
                fontSize = 11.sp,
                modifier = Modifier.width(16.dp),
            )
        }
        Text(text, color = Swim.Text, fontSize = 11.sp, maxLines = 1)
    }
}

/** A single-choice select. The first row clears the filter. */
@Composable
internal fun SwimSelect(
    label: String,
    selected: String?,
    options: List<SwimOption>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    anyLabel: String = "Any",
) {
    val current = options.firstOrNull { it.value == selected }?.label ?: selected
    MenuField(label, current, width, modifier) { close ->
        MenuRow(anyLabel, null) {
            close()
            onSelect(null)
        }
        options.forEach { option ->
            MenuRow(option.label, option.value == selected) {
                close()
                onSelect(option.value)
            }
        }
    }
}

/** A multi-choice select over a comma-joined value. The menu stays open while you tick. */
@Composable
internal fun SwimMultiSelect(
    label: String,
    selected: List<String>,
    options: List<SwimOption>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
) {
    val summary = when {
        selected.isEmpty() -> null
        selected.size == 1 -> options.firstOrNull { it.value == selected[0] }?.label ?: selected[0]
        else -> "${selected.size} selected"
    }
    MenuField(label, summary, width, modifier) { close ->
        MenuRow("Clear", null) {
            close()
            onChange(emptyList())
        }
        options.forEach { option ->
            val on = option.value in selected
            MenuRow(option.label, on) {
                onChange(if (on) selected - option.value else selected + option.value)
            }
        }
    }
}

/** A compact text field. [onSubmit] runs on Enter, [onEscape] on Esc. */
@Composable
internal fun SwimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    width: Dp = 130.dp,
    masked: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .width(width)
            .heightIn(min = ControlHeight)
            .background(if (hovered) Swim.CardHover else Swim.Bg, ControlShape)
            .border(1.dp, Swim.Border, ControlShape)
            .hoverable(interaction)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Swim.Text, fontSize = 11.sp),
            cursorBrush = SolidColor(Swim.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            visualTransformation = if (masked) MaskedText else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> onSubmit?.let { it(); true } ?: false
                        Key.Escape -> onEscape?.let { it(); true } ?: false
                        else -> false
                    }
                },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = Swim.Muted, fontSize = 11.sp, maxLines = 1)
                }
                inner()
            },
        )
    }
}

private val MaskedText = androidx.compose.ui.text.input.PasswordVisualTransformation('•')

/** A checkbox drawn as a glyph, so it matches the 11sp chrome. */
@Composable
internal fun SwimCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = ControlHeight)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(if (checked) "☑" else "☐", color = if (checked) Swim.Accent else Swim.Muted, fontSize = 12.sp)
        Text(label, color = if (checked) Swim.Text else Swim.TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

/** The "From: <path>" chip. */
@Composable
internal fun DismissibleChip(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Swim.Active, ControlShape)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, color = Swim.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = "✕",
            color = Swim.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.clickable { onDismiss() }.padding(horizontal = 4.dp),
        )
    }
}

/** A dismissible failure message. Mutations and loads both report here. */
@Composable
internal fun ErrorBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Swim.Red.copy(alpha = 0.15f))
            .border(1.dp, Swim.Red.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = Swim.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            text = "Dismiss",
            color = Swim.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}

/** The confirm step in front of every Linear mutation. */
@Composable
internal fun ConfirmDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .background(Swim.Card, RoundedCornerShape(8.dp))
                .border(1.dp, Swim.Border, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = Swim.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Swim.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.size(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                SwimButton("Cancel", onDismiss)
                SwimButton(confirmLabel, onConfirm, primary = true)
            }
        }
    }
}

/** A centered message for the empty, error and not-loaded bodies. */
@Composable
internal fun CenteredNotice(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    accent: Color = Swim.TextMuted,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.background(Swim.Bg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.defaultMinSize(minWidth = 240.dp).padding(24.dp),
        ) {
            Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(detail, color = Swim.TextMuted, fontSize = 12.sp)
            }
            action?.invoke()
        }
    }
}
