package swim.core.linear

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import swim.core.model.FilterOptions

/**
 * This function builds Linear's `IssueFilter` from resolved ids. The client changes team names
 * and project names into ids before it calls this function. The builder stays pure and easy
 * to test.
 *
 * Any state filter removes the default that excludes completed issues. `excludeLabel` is absent
 * here on purpose. The client applies it after the fetch, against the labels of each issue.
 */
internal fun buildIssueFilter(
    filters: FilterOptions,
    teamIds: List<String> = emptyList(),
    projectId: String? = null,
): JsonObject = buildJsonObject {
    when {
        teamIds.size == 1 -> put("team", comparator("id", "eq", JsonPrimitive(teamIds[0])))
        teamIds.size > 1 -> put("team", comparator("id", "in", JsonArray(teamIds.map(::JsonPrimitive))))
    }
    if (projectId != null) put("project", comparator("id", "eq", JsonPrimitive(projectId)))
    filters.label?.let { put("labels", comparator("name", "containsIgnoreCase", JsonPrimitive(it))) }
    filters.priority?.let { put("priority", buildJsonObject { put("eq", it) }) }

    val named = buildJsonObject {
        filters.state?.let { put("name", buildJsonObject { put("containsIgnoreCase", it) }) }
        val types = filters.stateType.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (types.isNotEmpty()) {
            put("type", buildJsonObject { put("in", JsonArray(types.map(::JsonPrimitive))) })
        }
    }
    val state = when {
        named.isNotEmpty() -> named
        filters.includeCompleted -> null
        else -> buildJsonObject {
            put("type", buildJsonObject { put("nin", JsonArray(DONE_STATE_TYPES.map(::JsonPrimitive))) })
        }
    }
    if (state != null) put("state", state)

    filters.assignee?.let { put("assignee", comparator("name", "containsIgnoreCase", JsonPrimitive(it))) }
    filters.cycleId?.let { put("cycle", comparator("id", "eq", JsonPrimitive(it))) }
}

private val DONE_STATE_TYPES = listOf("completed", "canceled")

private fun comparator(field: String, operator: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
    buildJsonObject { put(field, buildJsonObject { put(operator, value) }) }

/** Drops issues carrying `excludeLabel`, matched as a case-insensitive substring of a label name. */
internal fun applyExcludeLabel(issues: List<IssueWire>, excludeLabel: String?): List<IssueWire> {
    if (excludeLabel.isNullOrEmpty()) return issues
    val needle = excludeLabel.lowercase()
    return issues.filterNot { issue ->
        issue.labels.nodes.any { (it.name ?: "").lowercase().contains(needle) }
    }
}
