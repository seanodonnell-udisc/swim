@file:OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)

package swim.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.posix.STDOUT_FILENO
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.isatty
import platform.posix.stderr

/**
 * The output contract. Results go to stdout. Progress goes to stderr. Colour appears only on a
 * terminal, so a pipe and an agent both read plain text.
 */
object Out {
    /** True when stdout is a terminal. */
    val colored: Boolean = isatty(STDOUT_FILENO) == 1

    /** Writes one result line to stdout. */
    fun line(text: String = "") = println(text)

    /** Writes one progress note to stderr. */
    fun status(text: String) {
        fprintf(stderr, "%s\n", text)
        fflush(stderr)
    }

    /** Writes the uniform machine-readable payload to stdout. */
    fun json(command: String, scope: JsonElement, data: JsonElement, count: Int? = null) {
        val payload = buildJsonObject {
            put("command", command)
            put("scope", scope)
            if (count != null) put("count", count)
            put("data", data)
        }
        line(outJson.encodeToString(JsonObject.serializer(), payload))
    }
}

/** The payload format: two-space indent, absent instead of null, defaults written out. */
val outJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
    encodeDefaults = true
}

private const val ESC = "\u001B"

private fun paint(code: String, text: String): String =
    if (Out.colored) "$ESC[${code}m$text$ESC[0m" else text

fun bold(text: String): String = paint("1", text)
fun red(text: String): String = paint("31", text)
fun green(text: String): String = paint("32", text)
fun yellow(text: String): String = paint("33", text)
fun blue(text: String): String = paint("34", text)
fun cyan(text: String): String = paint("36", text)
fun gray(text: String): String = paint("90", text)

/** The colour the original CLI gives each priority. */
fun priorityColor(priority: Int): (String) -> String = when (priority) {
    1 -> ::red
    2 -> ::yellow
    3 -> ::blue
    else -> ::gray
}

/** Shortens `text` and marks the cut with an ellipsis. */
fun truncate(text: String, max: Int): String =
    if (text.length > max) text.substring(0, max - 1) + "…" else text

/** Drops the JSON keys that carry only a default, so an unset scope stays empty. */
fun JsonObject.withoutDefaults(): JsonObject = JsonObject(
    filterNot { (key, value) -> key == "includeCompleted" && value == JsonPrimitive(false) }
)
