package swim.ui.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import swim.core.url.isLinearUrl
import swim.ui.app.SwimTextField
import swim.ui.graph.Swim

/**
 * True when an edit should submit by itself. Compose has no paste event in common code, so a
 * paste is read as more than one character arriving at once; typed text never auto-submits.
 */
internal fun shouldAutoSubmit(previous: String, next: String): Boolean =
    next.length - previous.length > 1 && isLinearUrl(next)

/** The text a submit should send, or null when there is nothing to resolve. */
internal fun submitValue(text: String): String? = text.trim().ifEmpty { null }

/**
 * The Linear-URL field. Enter submits, a pasted Linear URL submits by itself, and Esc clears.
 */
@Composable
internal fun LinearUrlInput(
    resolving: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwimTextField(
            value = text,
            onValueChange = { next ->
                val auto = shouldAutoSubmit(text, next)
                text = next
                if (auto) submitValue(next)?.let(onSubmit)
            },
            placeholder = "Paste a Linear URL",
            width = 260.dp,
            onSubmit = { submitValue(text)?.let(onSubmit) },
            onEscape = { text = "" },
        )
        when {
            resolving -> Text("Resolving…", color = Swim.Accent, fontSize = 11.sp)
            error != null -> Text(error, color = Swim.Red, fontSize = 11.sp)
        }
    }
}
