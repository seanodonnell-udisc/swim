package swim.core.mermaid

import swim.core.blocks
import swim.core.duplicate
import swim.core.issue
import swim.core.model.DiagramOptions
import swim.core.model.WorkflowStateType
import swim.core.related
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidTest {
    @Test
    fun rendersAFlowchartWithNodesEdgesAndStateStyles() {
        val a = issue("MOB-1", state = "Backlog", stateType = WorkflowStateType.BACKLOG, priority = 1)
        val b = issue("MOB-2", state = "Done", stateType = WorkflowStateType.COMPLETED, priority = 0)
        val mermaid = generateFlowchart(listOf(a, b), listOf(blocks("MOB-1", "MOB-2")))

        val expected = """
            flowchart TD
              MOB_1["MOB-1: Title for MOB-1<br/><small>Backlog | Urgent</small>"]
              MOB_2["MOB-2: Title for MOB-2<br/><small>Done</small>"]

              MOB_1 -->|blocks| MOB_2

              style MOB_1 fill:#e0e0e0,stroke:#9e9e9e
              style MOB_2 fill:#36b37e,stroke:#1e7e34

        """.trimIndent()
        assertEquals(expected, mermaid)
    }

    @Test
    fun groupsNodesIntoSubgraphsByTeam() {
        val a = issue("MOB-1", team = "MOB", state = "Todo")
        val b = issue("WEB-1", team = "WEB", state = "Todo")
        val mermaid = generateFlowchart(listOf(a, b), emptyList(), DiagramOptions(groupBy = "team", showPriority = false))

        assertTrue(mermaid.contains("  subgraph MOB[\"MOB\"]\n    MOB_1[\"MOB-1: Title for MOB-1<br/><small>Todo</small>\"]\n  end\n"))
        assertTrue(mermaid.contains("  subgraph WEB[\"WEB\"]\n    WEB_1[\"WEB-1: Title for WEB-1<br/><small>Todo</small>\"]\n  end\n"))
    }

    @Test
    fun escapesQuotesBracketsBracesAndAngleBrackets() {
        val node = issue("MOB-1", state = "Todo").copy(title = """He said "hi" [x] {y} <b>""")
        val mermaid = generateFlowchart(listOf(node), emptyList(), DiagramOptions(showState = false, showPriority = false))
        assertTrue(mermaid.contains("""MOB_1["MOB-1: He said 'hi' (x) (y) &lt;b&gt;"]"""))
    }

    @Test
    fun truncatesLongTitlesToFortyCharsWithEllipsis() {
        val longTitle = "x".repeat(60)
        val node = issue("MOB-1", state = "Todo").copy(title = longTitle)
        val mermaid = generateFlowchart(listOf(node), emptyList(), DiagramOptions(showState = false, showPriority = false))
        val expectedLabel = "MOB-1: " + "x".repeat(37) + "..."
        assertTrue(mermaid.contains(expectedLabel))
    }

    @Test
    fun edgeStylesDifferByRelationType() {
        val nodes = listOf(issue("A"), issue("B"), issue("C"), issue("D"))
        val mermaid = generateFlowchart(
            nodes,
            listOf(
                blocks("A", "B"),
                related("A", "C"),
                duplicate("A", "D"),
            ),
        )
        assertTrue(mermaid.contains("A -->|blocks| B"))
        assertTrue(mermaid.contains("A -.-|related| C"))
        assertTrue(mermaid.contains("A -.->|duplicate| D"))
    }

    @Test
    fun directionIsConfigurable() {
        val mermaid = generateFlowchart(emptyList(), emptyList(), DiagramOptions(direction = "LR"))
        assertTrue(mermaid.startsWith("flowchart LR\n"))
    }

    @Test
    fun generatesTeamDependenciesWithIssueCountsAndBlockingCounts() {
        val nodes = listOf(
            issue("MOB-1", team = "MOB"),
            issue("MOB-2", team = "MOB"),
            issue("WEB-1", team = "WEB"),
        )
        val edges = listOf(blocks("MOB-1", "WEB-1"), blocks("MOB-2", "WEB-1"))
        val mermaid = generateTeamDependencies(nodes, edges)

        val expected = """
            flowchart LR
              MOB["MOB<br/><small>2 issues</small>"]
              WEB["WEB<br/><small>1 issues</small>"]

              MOB -->|2 blocking| WEB

        """.trimIndent()
        assertEquals(expected, mermaid)
    }
}
