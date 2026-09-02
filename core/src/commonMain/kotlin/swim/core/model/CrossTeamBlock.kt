package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CrossTeamBlock(val fromTeam: String, val toTeam: String, val count: Int)
