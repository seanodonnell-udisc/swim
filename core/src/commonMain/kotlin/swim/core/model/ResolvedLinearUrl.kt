package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedLinearUrl(
    val filters: FilterOptions,
    val singleIssueId: String? = null,
    val urlSource: String,
)
