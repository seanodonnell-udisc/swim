package swim.core.analysis

import swim.core.issue
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupingTest {
    @Test
    fun groupsByAssigneeFallingBackToUnassigned() {
        val nodes = listOf(issue("A", assignee = "Ada"), issue("B", assignee = null))
        val groups = groupIssues(nodes, GroupBy.ASSIGNEE)
        assertEquals(setOf("Ada", "Unassigned"), groups.keys)
    }

    @Test
    fun groupsByProjectFallingBackToNoProject() {
        val nodes = listOf(issue("A", project = "Launch"), issue("B", project = null))
        val groups = groupIssues(nodes, GroupBy.PROJECT)
        assertEquals(setOf("Launch", "No project"), groups.keys)
    }

    @Test
    fun groupsByTeamAndByState() {
        val nodes = listOf(issue("A", team = "MOB", state = "In Progress"))
        assertEquals(setOf("MOB"), groupIssues(nodes, GroupBy.TEAM).keys)
        assertEquals(setOf("In Progress"), groupIssues(nodes, GroupBy.STATE).keys)
    }

    @Test
    fun withinAGroupNoPrioritySinksLast() {
        val nodes = listOf(issue("A", priority = 0), issue("B", priority = 1), issue("C", priority = 4))
        val sorted = groupIssues(nodes, GroupBy.TEAM).getValue("MOB")
        assertEquals(listOf("B", "C", "A"), sorted.map { it.identifier })
    }

    @Test
    fun groupsByPriorityUsingPriorityLabels() {
        val nodes = listOf(issue("A", priority = 0), issue("B", priority = 1))
        val groups = groupIssues(nodes, GroupBy.PRIORITY)
        assertEquals(setOf("No priority", "Urgent"), groups.keys)
    }

    @Test
    fun groupsByMilestoneFallingBackToNoMilestone() {
        val nodes = listOf(issue("A", milestone = "M1 Foundations"), issue("B", milestone = null))
        val groups = groupIssues(nodes, GroupBy.MILESTONE)
        assertEquals(setOf("M1 Foundations", "No milestone"), groups.keys)
    }
}
