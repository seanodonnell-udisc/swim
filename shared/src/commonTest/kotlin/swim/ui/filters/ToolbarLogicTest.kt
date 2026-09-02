package swim.ui.filters

import swim.core.model.FilterOptions
import swim.core.model.LabelSummary
import swim.core.model.ProjectSummary
import swim.core.model.TeamSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val reference = ReferenceData(
    teams = listOf(
        TeamSummary("t1", "ENG", "Engineering"),
        TeamSummary("t2", "OPS", "Operations"),
    ),
    projects = listOf(
        ProjectSummary("p1", "Swim", "started", listOf("ENG")),
        ProjectSummary("p2", "Runway", "started", listOf("OPS")),
    ),
    labels = listOf(
        LabelSummary("l1", "bug", "#f00", null),
        LabelSummary("l2", "Bug", "#f00", "OPS"),
        LabelSummary("l3", "infra", "#0f0", "OPS"),
    ),
)

class ToolbarLogicTest {

    @Test
    fun teamsNarrowToTheSelectedProject() {
        val available = availablesOf(reference, FilterOptions(project = "Swim"))
        assertEquals(listOf("ENG"), available.teams.map { it.key })
    }

    @Test
    fun projectsNarrowToTheSelectedTeams() {
        val available = availablesOf(reference, FilterOptions(team = "OPS"))
        assertEquals(listOf("Runway"), available.projects.map { it.name })
    }

    @Test
    fun labelsDedupeByLowercasedName() {
        val available = availablesOf(reference, FilterOptions())
        assertEquals(listOf("bug", "infra"), available.labels.map { it.name })
    }

    @Test
    fun commaValuesRoundTrip() {
        assertEquals(listOf("ENG", "OPS"), commaValues("ENG, OPS"))
        assertEquals(emptyList(), commaValues(null))
        assertEquals("ENG,OPS", commaJoin(listOf("ENG", "OPS")))
        assertEquals(null, commaJoin(emptyList()))
    }

    @Test
    fun theLoadButtonOnlyReadsReloadAfterALoad() {
        assertEquals("Load issues", loadButtonLabel(loaded = false, armed = true))
        assertEquals("Load issues", loadButtonLabel(loaded = false, armed = false))
        assertEquals("Load issues", loadButtonLabel(loaded = true, armed = true))
        assertEquals("Reload", loadButtonLabel(loaded = true, armed = false))
    }

    @Test
    fun clearOnlyShowsWhenAFilterIsSet() {
        assertFalse(clearVisible(FilterOptions()))
        assertTrue(clearVisible(FilterOptions(team = "ENG")))
        assertTrue(clearVisible(FilterOptions(includeCompleted = true)))
    }

    @Test
    fun aPastedLinearUrlSubmitsButTypedTextDoesNot() {
        assertTrue(shouldAutoSubmit("", "https://linear.app/acme/issue/ENG-1"))
        assertFalse(shouldAutoSubmit("https://linear.app/acme/issue/ENG-", "https://linear.app/acme/issue/ENG-1"))
        assertFalse(shouldAutoSubmit("", "just some pasted words"))
    }

    @Test
    fun submitTrimsAndRefusesBlankText() {
        assertEquals("linear.app/x", submitValue("  linear.app/x  "))
        assertEquals(null, submitValue("   "))
    }
}
