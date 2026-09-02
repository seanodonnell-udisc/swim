package swim.core.linear

import swim.core.model.FilterOptions
import kotlin.test.Test
import kotlin.test.assertEquals

class IssueFilterTest {
    @Test
    fun emptyFiltersStillExcludeCompletedAndCanceled() {
        assertEquals(
            """{"state":{"type":{"nin":["completed","canceled"]}}}""",
            buildIssueFilter(FilterOptions()).toString(),
        )
    }

    @Test
    fun includeCompletedRemovesTheDefault() {
        assertEquals("{}", buildIssueFilter(FilterOptions(includeCompleted = true)).toString())
    }

    // Naming any state filter silently disables the exclude-completed default.
    @Test
    fun aStateNameDisablesTheDefault() {
        assertEquals(
            """{"state":{"name":{"containsIgnoreCase":"progress"}}}""",
            buildIssueFilter(FilterOptions(state = "progress")).toString(),
        )
    }

    @Test
    fun stateTypesArriveAsAList() {
        assertEquals(
            """{"state":{"type":{"in":["backlog","unstarted"]}}}""",
            buildIssueFilter(FilterOptions(stateType = "backlog, unstarted")).toString(),
        )
    }

    @Test
    fun oneTeamUsesEqAndManyTeamsUseIn() {
        assertEquals(
            """{"team":{"id":{"eq":"t1"}},"state":{"type":{"nin":["completed","canceled"]}}}""",
            buildIssueFilter(FilterOptions(team = "ENG"), teamIds = listOf("t1")).toString(),
        )
        assertEquals(
            """{"team":{"id":{"in":["t1","t2"]}},"state":{"type":{"nin":["completed","canceled"]}}}""",
            buildIssueFilter(FilterOptions(team = "ENG,OPS"), teamIds = listOf("t1", "t2")).toString(),
        )
    }

    @Test
    fun everyOtherFilterKeepsItsShapeAndOrder() {
        val filters = FilterOptions(
            label = "bug",
            priority = 2,
            assignee = "ada",
            cycleId = "cycle-1",
            includeCompleted = true,
        )
        assertEquals(
            """{"project":{"id":{"eq":"p1"}},"labels":{"name":{"containsIgnoreCase":"bug"}},""" +
                """"priority":{"eq":2},"assignee":{"name":{"containsIgnoreCase":"ada"}},""" +
                """"cycle":{"id":{"eq":"cycle-1"}}}""",
            buildIssueFilter(filters, projectId = "p1").toString(),
        )
    }

    @Test
    fun excludeLabelIsPostFilteredNotSentToLinear() {
        val filters = FilterOptions(excludeLabel = "Release", includeCompleted = true)
        assertEquals("{}", buildIssueFilter(filters).toString())

        val issues = listOf(
            IssueWire(identifier = "ENG-1", labels = Nodes(listOf(NameWire("release-2026")))),
            IssueWire(identifier = "ENG-2", labels = Nodes(listOf(NameWire("bug")))),
        )
        assertEquals(listOf("ENG-2"), applyExcludeLabel(issues, filters.excludeLabel).map { it.identifier })
    }
}
