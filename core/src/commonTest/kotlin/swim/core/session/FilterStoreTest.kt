package swim.core.session

import swim.core.model.FilterOptions
import swim.core.model.ResolvedLinearUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilterStoreTest {
    @Test
    fun aFreshStoreStartsEmptyAndUnloaded() {
        val store = FilterStore(FakeSettings())

        assertEquals(FilterState(), store.state.value)
    }

    @Test
    fun everyFilterSetterArmsALoadWithoutPerformingOne() {
        val store = FilterStore(FakeSettings())
        val edits: List<Pair<String, () -> Unit>> = listOf(
            "team" to { store.setTeam("MOB") },
            "project" to { store.setProject("Launch", "id-1") },
            "label" to { store.setLabel("bug") },
            "excludeLabel" to { store.setExcludeLabel("chore") },
            "priority" to { store.setPriority(2) },
            "state" to { store.setState("review") },
            "stateType" to { store.setStateType("started") },
            "assignee" to { store.setAssignee("Ada") },
            "includeCompleted" to { store.setIncludeCompleted(true) },
            "cycleId" to { store.setCycleId("cycle-1") },
            "setFilters" to { store.setFilters(FilterOptions(team = "WEB")) },
        )

        for ((name, edit) in edits) {
            store.applyFromUrl(ResolvedLinearUrl(FilterOptions(team = "OPS"), urlSource = "/team/OPS"))
            edit()
            assertFalse(store.state.value.shouldLoadIssues, "$name should not load")
            assertNull(store.state.value.urlSource, "$name should forget the URL")
        }
    }

    @Test
    fun theSettersWriteTheValuesTheyAreGiven() {
        val store = FilterStore(FakeSettings())

        store.setTeam("MOB,WEB")
        store.setProject("Launch", "id-1")
        store.setLabel("bug")
        store.setExcludeLabel("chore")
        store.setPriority(2)
        store.setState("review")
        store.setStateType("started")
        store.setAssignee("Ada")
        store.setIncludeCompleted(true)
        store.setCycleId("cycle-1")

        assertEquals(
            FilterOptions(
                team = "MOB,WEB",
                project = "Launch",
                projectId = "id-1",
                label = "bug",
                excludeLabel = "chore",
                priority = 2,
                state = "review",
                stateType = "started",
                assignee = "Ada",
                includeCompleted = true,
                cycleId = "cycle-1",
            ),
            store.filters,
        )
    }

    @Test
    fun clearingTheProjectClearsItsIdToo() {
        val store = FilterStore(FakeSettings())
        store.setProject("Launch", "id-1")
        store.setProject(null)

        assertNull(store.filters.project)
        assertNull(store.filters.projectId)
    }

    @Test
    fun applyFiltersIsWhatLoads() {
        val store = FilterStore(FakeSettings())
        store.setTeam("MOB")
        store.applyFilters()

        assertTrue(store.state.value.shouldLoadIssues)
        assertEquals("MOB", store.filters.team)
    }

    @Test
    fun applyFromUrlResetsAppliesRecordsAndLoads() {
        val store = FilterStore(FakeSettings())
        store.setTeam("MOB")
        store.setLabel("bug")
        store.setGroupBy(GraphGrouping.TEAM)

        store.applyFromUrl(
            ResolvedLinearUrl(FilterOptions(team = "WEB"), urlSource = "/team/WEB/cycle/1")
        )

        assertEquals(FilterOptions(team = "WEB"), store.filters)
        assertNull(store.filters.label)
        assertEquals("/team/WEB/cycle/1", store.state.value.urlSource)
        assertTrue(store.state.value.shouldLoadIssues)
        // Grouping is a view setting, so a URL does not reset it.
        assertEquals(GraphGrouping.TEAM, store.state.value.groupBy)
    }

    @Test
    fun clearFiltersResetsTheFiltersAndTheLoadFlag() {
        val store = FilterStore(FakeSettings())
        store.applyFromUrl(ResolvedLinearUrl(FilterOptions(team = "WEB"), urlSource = "/team/WEB"))
        store.setGroupBy(GraphGrouping.PROJECT)

        store.clearFilters()

        assertEquals(FilterOptions(), store.filters)
        assertFalse(store.state.value.shouldLoadIssues)
        assertNull(store.state.value.urlSource)
        assertEquals(GraphGrouping.PROJECT, store.state.value.groupBy)
    }

    @Test
    fun dismissingTheUrlSourceKeepsTheFiltersItProduced() {
        val store = FilterStore(FakeSettings())
        store.applyFromUrl(ResolvedLinearUrl(FilterOptions(team = "WEB"), urlSource = "/team/WEB"))

        store.dismissUrlSource()

        assertNull(store.state.value.urlSource)
        assertEquals("WEB", store.filters.team)
        assertTrue(store.state.value.shouldLoadIssues)
    }

    @Test
    fun groupingChangesDoNotArmALoad() {
        val store = FilterStore(FakeSettings())
        store.setTeam("MOB")
        store.applyFilters()

        store.setGroupBy(GraphGrouping.LABEL)

        assertEquals(GraphGrouping.LABEL, store.state.value.groupBy)
        assertTrue(store.state.value.shouldLoadIssues)
    }

    @Test
    fun filtersAndGroupingSurviveARestart() {
        val settings = FakeSettings()
        FilterStore(settings).apply {
            setTeam("MOB")
            setIncludeCompleted(true)
            setGroupBy(GraphGrouping.TEAM)
        }

        val restored = FilterStore(settings)

        assertEquals("MOB", restored.filters.team)
        assertTrue(restored.filters.includeCompleted)
        assertEquals(GraphGrouping.TEAM, restored.state.value.groupBy)
    }

    @Test
    fun theLoadFlagAndTheUrlSourceNeverSurviveARestart() {
        val settings = FakeSettings()
        FilterStore(settings).applyFromUrl(
            ResolvedLinearUrl(FilterOptions(team = "WEB"), urlSource = "/team/WEB")
        )

        val restored = FilterStore(settings)

        assertFalse(restored.state.value.shouldLoadIssues)
        assertNull(restored.state.value.urlSource)
        assertEquals("WEB", restored.filters.team)
    }

    @Test
    fun anUnreadableStoreStartsFromDefaults() {
        val settings = FakeSettings()
        settings.putString(FILTERS_KEY, "not json")

        assertEquals(FilterState(), FilterStore(settings).state.value)
    }
}
