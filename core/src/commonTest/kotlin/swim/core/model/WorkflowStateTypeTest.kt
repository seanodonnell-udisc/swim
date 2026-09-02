package swim.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowStateTypeTest {
    @Test
    fun roundTripsThroughLowercaseWireValues() {
        for (value in WorkflowStateType.entries) {
            val encoded = Json.encodeToString(value)
            assertEquals(encoded, encoded.lowercase())
            assertEquals(value, Json.decodeFromString<WorkflowStateType>(encoded))
        }
    }
}
