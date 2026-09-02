package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class RepoReference(val repo: String, val file: String, val line: Int, val issueId: String)

@Serializable
data class GapAnalysis(
    val identifier: String,
    val title: String,
    val referencedIn: List<String>,
    val missingIn: List<String>,
)
