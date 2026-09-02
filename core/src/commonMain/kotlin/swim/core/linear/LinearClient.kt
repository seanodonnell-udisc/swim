@file:OptIn(ExperimentalTime::class)

package swim.core.linear

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import swim.core.config.SwimConfig
import swim.core.model.ApiError
import swim.core.model.AuthError
import swim.core.model.FilterOptions
import swim.core.model.GraphData
import swim.core.model.IssueDetail
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.IssueRelationDetail
import swim.core.model.LabelSummary
import swim.core.model.NetworkError
import swim.core.model.NotFoundError
import swim.core.model.ProjectSummary
import swim.core.model.RateLimitedError
import swim.core.model.RelationType
import swim.core.model.ScopeError
import swim.core.model.SwimError
import swim.core.model.TeamSummary
import swim.core.model.UserSummary
import swim.core.model.Viewer
import swim.core.model.WorkflowStateType
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Supplies the `Authorization` header value for every Linear request. */
fun interface AuthHeaderProvider {
    suspend fun authorization(): String
}

/** Personal API keys go in the header raw. A `Bearer` prefix here is rejected. */
fun apiKeyAuth(apiKey: String): AuthHeaderProvider = AuthHeaderProvider { apiKey }

/** OAuth access tokens, and only OAuth tokens, carry the `Bearer` prefix. */
fun oauthAuth(accessToken: suspend () -> String): AuthHeaderProvider =
    AuthHeaderProvider { "Bearer ${accessToken()}" }

/** Fields an issue update may change. `clearAssignee` sends an explicit null. */
data class IssueUpdate(
    val title: String? = null,
    val description: String? = null,
    val priority: Int? = null,
    val stateId: String? = null,
    val labelIds: List<String>? = null,
    val projectId: String? = null,
    val assigneeId: String? = null,
    val clearAssignee: Boolean = false,
)

/**
 * Linear's GraphQL API. Each request is one Ktor POST. The client keeps reference data for
 * five minutes. The client reads issues in bulk, with every nested field inline.
 */
class LinearClient(
    private val http: HttpClient,
    private val auth: AuthHeaderProvider,
    private val config: SwimConfig = SwimConfig(),
) {
    private val teamCache = TtlCache<List<TeamSummary>>()
    private val projectCache = TtlCache<List<ProjectSummary>>()
    private val labelCache = TtlCache<List<LabelSummary>>()

    // ---- reference data -----------------------------------------------------------------

    /** Every team, cached for five minutes. */
    suspend fun getTeams(): List<TeamSummary> = teamCache.get("") {
        execute(TEAMS_QUERY, emptyVariables(), TeamsData.serializer())
            .teams.nodes.map { TeamSummary(id = it.id, key = it.key, name = it.name) }
    }

    /** A team by name or key, case-insensitive. */
    suspend fun getTeamByName(name: String): TeamSummary? = getTeams().firstOrNull {
        it.name.equals(name, ignoreCase = true) || it.key.equals(name, ignoreCase = true)
    }

    /** Projects, all of them or the ones a team can reach. Cached for five minutes. */
    suspend fun getProjects(teamId: String? = null): List<ProjectSummary> =
        projectCache.get(teamId ?: "") {
            val data = if (teamId == null) {
                execute(PROJECTS_QUERY, emptyVariables(), ProjectsData.serializer())
            } else {
                execute(
                    PROJECTS_BY_TEAM_QUERY,
                    buildJsonObject { put("teamId", teamId) },
                    ProjectsData.serializer(),
                )
            }
            data.projects.nodes.map { ProjectSummary(id = it.id, name = it.name, state = it.state) }
        }

    /** The first project whose name contains `name`, case-insensitive. */
    suspend fun getProjectByName(name: String): ProjectSummary? =
        getProjects().firstOrNull { it.name.contains(name, ignoreCase = true) }

    /** Labels, all of them or one team's. Cached for five minutes. */
    suspend fun getLabels(teamId: String? = null): List<LabelSummary> = labelCache.get(teamId ?: "") {
        val labels = paginate(LABEL_PAGE_SIZE) { first, after ->
            val variables = buildJsonObject {
                if (teamId != null) put("teamId", teamId)
                put("first", first)
                if (after != null) put("after", after)
            }
            val query = if (teamId == null) LABELS_QUERY else LABELS_BY_TEAM_QUERY
            execute(query, variables, LabelsData.serializer()).issueLabels
        }
        hideVersionLabels(labels.map {
            LabelSummary(id = it.id, name = it.name, color = it.color, team = it.team?.key)
        })
    }

    /** Active users, sorted by name. */
    suspend fun getUsers(): List<UserSummary> =
        execute(USERS_QUERY, emptyVariables(), UsersData.serializer())
            .users.nodes.map { UserSummary(id = it.id, name = it.name) }.sortedBy { it.name }

    /** The signed-in user. The `auth` command uses this to prove the credentials work. */
    suspend fun getViewer(): Viewer =
        execute(VIEWER_QUERY, emptyVariables(), ViewerData.serializer())
            .viewer.let { Viewer(name = it.name, email = it.email) }

    /** Open projects with their team keys, for the filter bar. */
    suspend fun getProjectSummaries(): List<ProjectSummary> =
        paginate(PROJECT_SUMMARY_PAGE_SIZE) { first, after ->
            val variables = buildJsonObject {
                put("first", first)
                if (after != null) put("after", after)
            }
            execute(PROJECT_SUMMARIES_QUERY, variables, ProjectPageData.serializer()).projects
        }.map { project ->
            ProjectSummary(
                id = project.id,
                name = project.name,
                state = project.state,
                teams = project.teams?.nodes?.mapNotNull { it.key }.orEmpty(),
            )
        }.sortedBy { it.name }

    /** Every label with its owning team, for the filter bar. */
    suspend fun getLabelSummaries(): List<LabelSummary> = getLabels().sortedBy { it.name }

    // ---- issues -------------------------------------------------------------------------

    /** Issues matching the filters, hydrated but without relations. */
    suspend fun getIssueNodes(filters: FilterOptions = FilterOptions()): List<IssueNode> =
        fetchIssues(filters, withRelations = false).map { it.toIssueNode() }

    /** Issues matching the filters, with relations, as graph data. */
    suspend fun getIssuesWithRelations(filters: FilterOptions = FilterOptions()): GraphData =
        toGraph(fetchIssues(filters, withRelations = true))

    /** One issue by identifier, with its relations, in a single request. Null when it does not exist. */
    suspend fun getIssueDetail(identifier: String): IssueDetail? {
        val (teamKey, number) = splitIdentifier(identifier) ?: return null
        val raw = execute(
            ISSUE_BY_IDENTIFIER_QUERY,
            buildJsonObject { put("teamKey", teamKey); put("number", number) },
            IssueNodesData.serializer(),
        ).issues.nodes.firstOrNull() ?: return null

        val relations = buildList {
            raw.relations?.nodes.orEmpty().forEach { relation ->
                val other = relation.relatedIssue ?: return@forEach
                add(relationDetail(relation.id, relation.type, other))
            }
            raw.inverseRelations?.nodes.orEmpty().forEach { relation ->
                val other = relation.issue ?: return@forEach
                val type = if (relation.type.equals("blocks", ignoreCase = true)) "blocked by" else relation.type
                add(relationDetail(relation.id, type, other))
            }
        }
        return IssueDetail(node = raw.toIssueNode(), relations = relations)
    }

    // ---- mutations ----------------------------------------------------------------------

    /** Creates `from <type> to`. Returns the new relation id. */
    suspend fun createIssueRelation(from: String, to: String, type: RelationType): String {
        val issueId = resolveIssueUuid(from)
        val relatedIssueId = resolveIssueUuid(to)
        val payload = execute(
            CREATE_RELATION_MUTATION,
            buildJsonObject {
                put("issueId", issueId)
                put("relatedIssueId", relatedIssueId)
                put("type", type.wire)
            },
            RelationCreateData.serializer(),
        ).issueRelationCreate
        if (!payload.success) throw ApiError("Linear refused to create the relation $from $type $to.")
        return payload.issueRelation?.id.orEmpty()
    }

    /** Deletes one relation by its Linear relation id. */
    suspend fun deleteIssueRelation(relationId: String) {
        val payload = execute(
            DELETE_RELATION_MUTATION,
            buildJsonObject { put("id", relationId) },
            RelationDeleteData.serializer(),
        ).issueRelationDelete
        if (!payload.success) throw ApiError("Linear refused to delete relation $relationId.")
    }

    /** Applies a partial update to one issue, named by identifier or UUID. */
    suspend fun updateIssue(issueRef: String, update: IssueUpdate) {
        val input = buildJsonObject {
            update.title?.let { put("title", it) }
            update.description?.let { put("description", it) }
            update.priority?.let { put("priority", it) }
            update.stateId?.let { put("stateId", it) }
            update.labelIds?.let { put("labelIds", JsonArray(it.map(::JsonPrimitive))) }
            update.projectId?.let { put("projectId", it) }
            when {
                update.clearAssignee -> put("assigneeId", JsonNull)
                update.assigneeId != null -> put("assigneeId", update.assigneeId)
            }
        }
        if (input.isEmpty()) return
        val payload = execute(
            UPDATE_ISSUE_MUTATION,
            buildJsonObject { put("id", resolveIssueUuid(issueRef)); put("input", input) },
            IssueUpdateData.serializer(),
        ).issueUpdate
        if (!payload.success) throw ApiError("Linear refused to update $issueRef.")
    }

    /** Assigns an issue, or unassigns it when `userId` is null. */
    suspend fun setAssignee(issueRef: String, userId: String?) = updateIssue(
        issueRef,
        if (userId == null) IssueUpdate(clearAssignee = true) else IssueUpdate(assigneeId = userId),
    )

    /** Resolves an identifier such as `ENG-123`, or a UUID, to the issue UUID. */
    suspend fun resolveIssueUuid(issueRef: String): String {
        val parts = splitIdentifier(issueRef)
        if (parts != null) {
            val (teamKey, number) = parts
            val found = execute(
                ISSUE_ID_BY_IDENTIFIER_QUERY,
                buildJsonObject { put("teamKey", teamKey); put("number", number) },
                IssueIdsData.serializer(),
            ).issues.nodes.firstOrNull()
            if (found != null) return found.id
            throw NotFoundError("No issue ${issueRef.uppercase()}.")
        }
        val byId = execute(
            ISSUE_BY_UUID_QUERY,
            buildJsonObject { put("id", issueRef) },
            IssueByIdData.serializer(),
        ).issue
        return byId?.id ?: throw NotFoundError("No issue $issueRef.")
    }

    // ---- internals ----------------------------------------------------------------------

    private suspend fun fetchIssues(filters: FilterOptions, withRelations: Boolean): List<IssueWire> {
        val filter = resolveFilter(filters)
        val query = if (withRelations) ISSUES_WITH_RELATIONS_QUERY else ISSUES_QUERY
        val issues = paginate(ISSUE_PAGE_SIZE) { first, after ->
            val variables = buildJsonObject {
                if (filter.isNotEmpty()) put("filter", filter)
                put("first", first)
                if (after != null) put("after", after)
            }
            execute(query, variables, IssuesData.serializer()).issues
        }
        return applyExcludeLabel(issues, filters.excludeLabel)
    }

    /** Resolves team and project names to ids, then builds the GraphQL filter. */
    private suspend fun resolveFilter(filters: FilterOptions): JsonObject {
        val teamIds = mutableListOf<String>()
        filters.team?.let { raw ->
            val names = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val missing = mutableListOf<String>()
            for (name in names) {
                val team = getTeamByName(name)
                if (team == null) missing += name else teamIds += team.id
            }
            if (missing.isNotEmpty()) {
                throw ScopeError("Unknown team: ${missing.joinToString(", ")}. Run `swim teams` to list teams.")
            }
        }
        // The id is preferred over the name: two projects with overlapping names collide by name.
        val projectId = filters.projectId ?: filters.project?.let { name ->
            getProjectByName(name)?.id
                ?: throw ScopeError("Unknown project: $name. Run `swim projects` to list projects.")
        }
        return buildIssueFilter(filters, teamIds, projectId)
    }

    private fun hideVersionLabels(labels: List<LabelSummary>): List<LabelSummary> =
        if (config.showVersionLabels) labels else labels.filterNot { SEMVER_LABEL.matches(it.name.trim()) }

    private suspend fun <T> paginate(pageSize: Int, fetch: suspend (Int, String?) -> Connection<T>): List<T> {
        val all = mutableListOf<T>()
        var cursor: String? = null
        while (true) {
            val page = fetch(pageSize, cursor)
            all += page.nodes
            if (!page.pageInfo.hasNextPage) break
            cursor = page.pageInfo.endCursor ?: break
        }
        return all
    }

    private suspend fun <T> execute(
        query: String,
        variables: JsonObject,
        serializer: KSerializer<T>,
    ): T {
        var retried = false
        while (true) {
            // Resolved outside the catch: an expired session is an AuthError, not a network one.
            val authorization = auth.authorization()
            val (response, body) = try {
                val answer = http.post(GRAPHQL_URL) {
                    header(HttpHeaders.Authorization, authorization)
                    contentType(ContentType.Application.Json)
                    setBody(
                        linearJson.encodeToString(
                            GraphQlRequest.serializer(),
                            GraphQlRequest(query, variables),
                        )
                    )
                }
                answer to answer.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SwimError) {
                throw e
            } catch (e: Exception) {
                throw NetworkError("Could not reach Linear: ${e.message}", e)
            }

            val envelope = try {
                linearJson.decodeFromString(GraphQlEnvelope.serializer(serializer), body)
            } catch (e: Exception) {
                null
            }
            val errors = envelope?.errors.orEmpty()

            // Linear answers a rate limit with HTTP 400 and a RATELIMITED error, not with 429.
            if (response.status == HttpStatusCode.TooManyRequests || errors.any { it.code == "RATELIMITED" }) {
                val wait = retryDelay(response.headers)
                if (!retried && wait != null) {
                    retried = true
                    delay(wait)
                    continue
                }
                throw RateLimitedError("Linear rate limit reached. Try again later.", wait)
            }
            if (response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ||
                errors.any { it.code == "AUTHENTICATION_ERROR" }
            ) {
                throw AuthError("Linear rejected the credentials. Run `swim auth` to sign in again.")
            }
            if (errors.isNotEmpty()) throw ApiError(errors.joinToString("; ") { it.message })
            if (!response.status.isSuccess()) throw ApiError("Linear returned ${response.status}.")
            return envelope?.data ?: throw ApiError("Linear returned no data.")
        }
    }

    private fun retryDelay(headers: Headers): Duration? {
        val retryAfter = headers["Retry-After"]?.toLongOrNull()?.seconds
        val reset = headers["X-RateLimit-Requests-Reset"]?.toLongOrNull()?.let { value ->
            val resetAt = if (value > EPOCH_SECONDS_CEILING) {
                Instant.fromEpochMilliseconds(value)
            } else {
                Instant.fromEpochSeconds(value)
            }
            resetAt - Clock.System.now()
        }
        val wait = retryAfter ?: reset ?: DEFAULT_RETRY_WAIT
        if (wait > MAX_RETRY_WAIT) return null
        return wait.coerceAtLeast(Duration.ZERO)
    }
}

/**
 * Every relation arrives two times. The issue that owns the relation reports it, and the other
 * side reports it again. The client removes the repeated relations by relation id. Blockers
 * outside the fetched set keep their state type, which keeps readiness correct under any filter.
 */
internal fun toGraph(raw: List<IssueWire>): GraphData {
    val nodes = raw.map { it.toIssueNode() }
    val fetched = nodes.mapTo(mutableSetOf()) { it.identifier }
    val edges = mutableListOf<IssueEdge>()
    val seen = mutableSetOf<String>()
    val externalBlockerStates = mutableMapOf<String, WorkflowStateType>()

    fun addEdge(relationId: String, wireType: String, from: String, to: String) {
        val type = relationTypeOf(wireType) ?: return
        if (!seen.add(relationId)) return
        edges += IssueEdge(from = from, to = to, type = type, relationId = relationId)
    }

    raw.forEachIndexed { index, wire ->
        val identifier = nodes[index].identifier
        wire.relations?.nodes.orEmpty().forEach { relation ->
            val other = relation.relatedIssue ?: return@forEach
            addEdge(relation.id, relation.type, identifier, other.identifier)
        }
        wire.inverseRelations?.nodes.orEmpty().forEach { relation ->
            val other = relation.issue ?: return@forEach
            addEdge(relation.id, relation.type, other.identifier, identifier)
            if (relation.type.equals("blocks", ignoreCase = true) &&
                other.identifier !in fetched &&
                other.state != null
            ) {
                externalBlockerStates[other.identifier] = workflowStateTypeOf(other.state.type)
            }
        }
    }
    return GraphData(nodes = nodes, edges = edges, externalBlockerStates = externalBlockerStates)
}

private class TtlCache<T>(private val ttl: Duration = CACHE_TTL) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Pair<Instant, T>>()

    suspend fun get(key: String, load: suspend () -> T): T = mutex.withLock {
        val hit = entries[key]
        if (hit != null && Clock.System.now() - hit.first < ttl) return@withLock hit.second
        val value = load()
        entries[key] = Clock.System.now() to value
        value
    }
}

private fun relationDetail(id: String, type: String, other: RelatedIssueWire) = IssueRelationDetail(
    type = type,
    identifier = other.identifier,
    title = other.title,
    state = other.state?.name ?: "Unknown",
    relationId = id,
)

private val IDENTIFIER = Regex("^([A-Z0-9]+)-(\\d+)$")

/** Splits `ENG-123` into its team key and issue number. Null when the text is not an identifier. */
private fun splitIdentifier(ref: String): Pair<String, Double>? {
    val match = IDENTIFIER.matchEntire(ref.trim().uppercase()) ?: return null
    val number = match.groupValues[2].toDoubleOrNull() ?: return null
    return match.groupValues[1] to number
}

private val RelationType.wire: String
    get() = when (this) {
        RelationType.BLOCKS -> "blocks"
        RelationType.RELATED -> "related"
        RelationType.DUPLICATE -> "duplicate"
    }

private fun emptyVariables(): JsonObject = JsonObject(emptyMap())

private val linearJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private val SEMVER_LABEL = Regex("^v?\\d+\\.\\d+\\.\\d+(-[\\w.]+)?$", RegexOption.IGNORE_CASE)

private const val GRAPHQL_URL = "https://api.linear.app/graphql"
private const val ISSUE_PAGE_SIZE = 250
private const val LABEL_PAGE_SIZE = 250

// The nested teams connection multiplies query complexity; 50 stays under Linear's cap.
private const val PROJECT_SUMMARY_PAGE_SIZE = 50
private const val EPOCH_SECONDS_CEILING = 100_000_000_000L
private val CACHE_TTL = 5.minutes
private val MAX_RETRY_WAIT = 30.seconds
private val DEFAULT_RETRY_WAIT = 1.seconds
