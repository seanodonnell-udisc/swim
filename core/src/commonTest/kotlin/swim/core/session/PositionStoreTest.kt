package swim.core.session

import swim.core.model.FilterOptions
import swim.layout.Position
import swim.layout.PositionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PositionStoreTest {
    @Test
    fun anEmptyStoreReadsAsAnEmptySnapshot() {
        assertEquals(PositionSnapshot(), SettingsPositionStore(FakeSettings()).get())
    }

    @Test
    fun aSnapshotRoundTrips() {
        val settings = FakeSettings()
        val snapshot = PositionSnapshot(
            mapOf(
                "query-a" to mapOf("MOB-1" to Position(10f, 20f), "MOB-2" to Position(-5f, 0.5f)),
                "query-b" to mapOf("WEB-1" to Position(300f, 140f)),
            )
        )

        SettingsPositionStore(settings).set(snapshot)

        assertEquals(snapshot, SettingsPositionStore(settings).get())
    }

    @Test
    fun anUnreadableStoreReadsAsEmpty() {
        val settings = FakeSettings()
        settings.putString(POSITIONS_KEY, "{oops")

        assertEquals(PositionSnapshot(), SettingsPositionStore(settings).get())
    }

    @Test
    fun theCacheKeyIsStableForTheSameQuery() {
        val filters = FilterOptions(team = "MOB", label = "bug")

        assertEquals(
            cacheKey(filters, GraphGrouping.TEAM),
            cacheKey(FilterOptions(team = "MOB", label = "bug"), GraphGrouping.TEAM),
        )
    }

    @Test
    fun theCacheKeySeparatesFiltersAndGrouping() {
        val filters = FilterOptions(team = "MOB")

        assertNotEquals(cacheKey(filters, GraphGrouping.NONE), cacheKey(filters, GraphGrouping.TEAM))
        assertNotEquals(
            cacheKey(filters, GraphGrouping.NONE),
            cacheKey(FilterOptions(team = "WEB"), GraphGrouping.NONE),
        )
    }

    @Test
    fun theGroupingIsReadableBackOffTheCacheKey() {
        GraphGrouping.entries.forEach { grouping ->
            val key = cacheKey(FilterOptions(team = "ENG"), grouping)
            assertEquals(grouping, groupingOf(key))
        }
        assertNull(groupingOf("not a cache key"))
    }
}
