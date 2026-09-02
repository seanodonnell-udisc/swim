package swim.core.url

import io.ktor.http.Url
import swim.core.linear.LinearClient
import swim.core.model.FilterOptions
import swim.core.model.ResolvedLinearUrl
import swim.core.model.ScopeError

/**
 * Turns a pasted Linear URL into filters. Project URLs are resolved against the workspace, so
 * this suspends. Unlike the original, a resolved project carries its id as well as its name:
 * two projects with overlapping names no longer collide when the filter is applied.
 */
suspend fun resolveLinearUrl(url: String, client: LinearClient): ResolvedLinearUrl {
    val parsed = when (val result = parseLinearUrl(url)) {
        is ParseResult.Success -> result.data
        is ParseResult.Failure -> throw ScopeError(result.error)
    }

    var filters = FilterOptions(
        team = parsed.teamKey,
        priority = parsed.queryParams?.priority,
        state = parsed.queryParams?.state,
        assignee = parsed.queryParams?.assignee,
        label = parsed.queryParams?.label,
    )
    var singleIssueId: String? = null

    when (parsed.type) {
        LinearUrlType.CYCLE -> filters = filters.copy(cycleId = parsed.cycleId)
        LinearUrlType.PROJECT -> {
            val project = parsed.projectId?.let { fragment ->
                client.getProjects().firstOrNull { it.id.contains(fragment, ignoreCase = true) }
            } ?: parsed.projectSlug?.let { slug ->
                val approximateName = slug.replace("-", " ")
                client.getProjects().firstOrNull { it.name.contains(approximateName, ignoreCase = true) }
            }
            if (project != null) filters = filters.copy(project = project.name, projectId = project.id)
        }
        LinearUrlType.ISSUE -> singleIssueId = parsed.issueIdentifier
        LinearUrlType.TEAM, LinearUrlType.FILTERED -> Unit
    }

    return ResolvedLinearUrl(filters = filters, singleIssueId = singleIssueId, urlSource = urlSourceOf(url))
}

/** The `path?query` part, for the "From: …" chip. Falls back to the raw text. */
private fun urlSourceOf(url: String): String {
    val trimmed = url.trim()
    val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    return try {
        val parsed = Url(if (hasScheme) trimmed else "https://$trimmed")
        val query = parsed.encodedQuery
        if (query.isEmpty()) parsed.encodedPath else "${parsed.encodedPath}?$query"
    } catch (e: Exception) {
        url
    }
}
