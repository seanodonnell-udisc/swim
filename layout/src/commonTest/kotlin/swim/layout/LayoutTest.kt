package swim.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutTest {
    @Test
    fun emptyGraphProducesNoPositions() {
        assertEquals(emptyMap(), layout(nodes = emptyList(), edges = emptyList()).positions)
    }
}
