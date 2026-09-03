package swim.ui.app

import swim.core.model.FilterOptions
import swim.core.session.FilterStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panel shows the query as summary chips only; the controls live in the modal. These are the
 * two halves of that: what the chips say, and what dismissing one clears.
 */
class SidePanelLogicTest {

    @Test
    fun anEmptyQueryHasNoChips() {
        assertTrue(activeFilters(FilterOptions()).isEmpty())
    }

    @Test
    fun everyTeamGetsItsOwnChipSoEachCanCarryItsOwnLink() {
        val chips = activeFilters(FilterOptions(team = "ENG,OPS"))

        assertEquals(listOf("Team: ENG", "Team: OPS"), chips.map { it.text })
        assertEquals(listOf("ENG", "OPS"), chips.map { it.value })
        assertTrue(chips.all { it.field == FilterField.TEAM })
    }

    @Test
    fun everyFilterThatIsSetReadsBackAsOneChip() {
        val chips = activeFilters(
            FilterOptions(
                team = "ENG",
                project = "Swim",
                label = "bug",
                excludeLabel = "chore",
                priority = 1,
                stateType = "started",
                state = "Review",
                assignee = "kim",
                includeCompleted = true,
            )
        )

        assertEquals(
            listOf(
                "Team: ENG",
                "Project: Swim",
                "Label: bug",
                "Exclude: chore",
                "Priority: Urgent",
                "Status: started",
                "State: Review",
                "Assignee: kim",
                "Completed included",
            ),
            chips.map { it.text },
        )
    }

    @Test
    fun dismissingOneTeamChipLeavesTheOtherTeamsAlone() {
        val store = FilterStore(FakeSettings())
        store.setTeam("ENG,OPS,WEB")

        clearFilter(store, activeFilters(store.filters).first { it.value == "OPS" })

        assertEquals("ENG,WEB", store.filters.team)
    }

    @Test
    fun dismissingTheLastTeamChipClearsTheFilter() {
        val store = FilterStore(FakeSettings())
        store.setTeam("ENG")

        clearFilter(store, activeFilters(store.filters).single())

        assertEquals(null, store.filters.team)
        assertTrue(activeFilters(store.filters).isEmpty())
    }

    @Test
    fun everyChipClearsTheFilterItStandsFor() {
        val full = FilterOptions(
            team = "ENG",
            project = "Swim",
            label = "bug",
            excludeLabel = "chore",
            priority = 1,
            stateType = "started",
            state = "Review",
            assignee = "kim",
            includeCompleted = true,
        )
        val store = FilterStore(FakeSettings())
        store.setFilters(full)

        // One chip at a time, always the first one left, until the query is empty again.
        while (true) {
            val chip = activeFilters(store.filters).firstOrNull() ?: break
            clearFilter(store, chip)
        }

        assertEquals(FilterOptions(), store.filters)
    }
}
