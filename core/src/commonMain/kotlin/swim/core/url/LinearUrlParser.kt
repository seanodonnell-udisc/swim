package swim.core.url

import io.ktor.http.Url

enum class LinearUrlType { ISSUE, PROJECT, TEAM, FILTERED, CYCLE }

data class LinearUrlQueryParams(
    val priority: Int? = null,
    val state: String? = null,
    val assignee: String? = null,
    val label: String? = null,
) {
    val isEmpty: Boolean get() = priority == null && state == null && assignee == null && label == null
}

data class ParsedLinearUrl(
    val type: LinearUrlType,
    val teamKey: String? = null,
    val cycleId: String? = null,
    val projectSlug: String? = null,
    val projectId: String? = null,
    val issueIdentifier: String? = null,
    val queryParams: LinearUrlQueryParams? = null,
)

sealed class ParseResult {
    data class Success(val data: ParsedLinearUrl) : ParseResult()
    data class Failure(val error: String) : ParseResult()
}

private val ISSUE_IDENTIFIER = Regex("^[A-Z0-9]+-\\d+$", RegexOption.IGNORE_CASE)
private val PROJECT_UUID_SUFFIX = Regex("-([a-f0-9]{12})$", RegexOption.IGNORE_CASE)
private val CYCLE_UUID = Regex("^[0-9a-f-]{36}$", RegexOption.IGNORE_CASE)

/** Parses a Linear issue, team, team-cycle, or project URL into structured filter data. */
fun parseLinearUrl(url: String): ParseResult {
    val trimmed = url.trim()
    val hasScheme = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    val normalized = if (hasScheme) trimmed else "https://$trimmed"

    val parsed = try {
        Url(normalized)
    } catch (e: Exception) {
        return ParseResult.Failure("Invalid URL: $url")
    }
    if (!parsed.host.endsWith("linear.app")) return ParseResult.Failure("Not a Linear URL")

    val segments = parsed.encodedPath.split("/").filter { it.isNotEmpty() }
    val kind = segments.getOrNull(1)
    val a = segments.getOrNull(2)
    val b = segments.getOrNull(3)
    val c = segments.getOrNull(4)
    val params = parseQueryParams(parsed)
    val queryParams = params.takeUnless { it.isEmpty }

    if (kind == "issue" && a != null && ISSUE_IDENTIFIER.matches(a)) {
        val issueIdentifier = a.uppercase()
        return ParseResult.Success(
            ParsedLinearUrl(type = LinearUrlType.ISSUE, teamKey = issueIdentifier.substringBefore("-"), issueIdentifier = issueIdentifier)
        )
    }

    if (kind == "project" && a != null) {
        val slug = decodePercentEncoded(a)
        val uuidMatch = PROJECT_UUID_SUFFIX.find(slug)
        return ParseResult.Success(
            ParsedLinearUrl(
                type = LinearUrlType.PROJECT,
                projectId = uuidMatch?.groupValues?.get(1),
                projectSlug = if (uuidMatch != null) slug.dropLast(13) else slug,
                queryParams = queryParams,
            )
        )
    }

    if (kind == "team" && a != null) {
        val teamKey = a.uppercase()
        if (b == "cycle" && c != null && CYCLE_UUID.matches(c)) {
            return ParseResult.Success(ParsedLinearUrl(type = LinearUrlType.CYCLE, teamKey = teamKey, cycleId = c, queryParams = queryParams))
        }
        val type = if (queryParams != null) LinearUrlType.FILTERED else LinearUrlType.TEAM
        return ParseResult.Success(ParsedLinearUrl(type = type, teamKey = teamKey, queryParams = queryParams))
    }

    if (kind == "view") return ParseResult.Failure("Custom views cannot be queried through the API")
    return ParseResult.Failure("Unrecognized Linear URL. Supported: issue, team, team cycle, and project URLs.")
}

private fun parseQueryParams(parsed: Url): LinearUrlQueryParams {
    val priority = parsed.parameters["priority"]?.toIntOrNull()?.takeIf { it in 0..4 }
    return LinearUrlQueryParams(
        priority = priority,
        state = parsed.parameters["state"],
        assignee = parsed.parameters["assignee"],
        label = parsed.parameters["label"],
    )
}

private fun decodePercentEncoded(input: String): String {
    if ("%" !in input) return input
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < input.length) {
        if (input[i] == '%' && i + 2 < input.length) {
            val value = input.substring(i + 1, i + 3).toIntOrNull(16)
            if (value != null) {
                bytes.add(value.toByte())
                i += 3
                continue
            }
        }
        val start = i
        while (i < input.length && input[i] != '%') i++
        bytes.addAll(input.substring(start, i).encodeToByteArray().toList())
    }
    return bytes.toByteArray().decodeToString()
}

/** True when `text` looks like a Linear URL. */
fun isLinearUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.contains("linear.app/") || trimmed.startsWith("linear.app")
}
