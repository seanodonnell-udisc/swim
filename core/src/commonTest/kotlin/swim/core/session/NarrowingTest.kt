package swim.core.session

import swim.core.model.FilterOptions
import swim.core.model.LabelSummary
import swim.core.model.ProjectSummary
import swim.core.model.TeamSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun team(key: String) = TeamSummary(id = "id-$key", key = key, name = "$key team")

private fun project(name: String, vararg teams: String) =
    ProjectSummary(id = "id-$name", name = name, state = "started", teams = teams.toList())

private fun label(name: String, team: String? = null) =
    LabelSummary(id = "id-$name-${team ?: "ws"}", name = name, color = "#fff", team = team)

class NarrowingTest {
    private val teams = listOf(team("MOB"), team("WEB"), team("OPS"))
    private val projects = listOf(
        project("Launch", "MOB", "WEB"),
        project("Infra", "OPS"),
        project("Everything"),
    )

    @Test
    fun teamKeysSplitsTrimsAndDropsEmptyEntries() {
        assertEquals(listOf("MOB", "WEB"), teamKeys(" MOB , WEB ,, "))
        assertEquals(emptyList(), teamKeys(null))
        assertEquals(emptyList(), teamKeys(""))
    }

    @Test
    fun everyTeamIsAvailableWithNoProjectSelected() {
        assertEquals(teams, availableTeams(teams, null))
    }

    @Test
    fun onlyTheSelectedProjectsTeamsAreAvailable() {
        assertEquals(listOf("MOB", "WEB"), availableTeams(teams, projects[0]).map { it.key })
    }

    @Test
    fun aProjectWithNoTeamsNarrowsTeamsToNothing() {
        assertEquals(emptyList(), availableTeams(teams, projects[2]))
    }

    @Test
    fun everyProjectIsAvailableWithNoTeamSelected() {
        assertEquals(projects, availableProjects(projects, emptyList()))
    }

    @Test
    fun onlyProjectsSharingATeamWithTheSelectionAreAvailable() {
        assertEquals(listOf("Launch"), availableProjects(projects, listOf("WEB")).map { it.name })
        assertEquals(
            listOf("Launch", "Infra"),
            availableProjects(projects, listOf("MOB", "OPS")).map { it.name },
        )
    }

    @Test
    fun workspaceLabelsSurviveEveryTeamSelection() {
        val labels = listOf(label("bug"), label("mobile-only", "MOB"))
        assertEquals(listOf("bug"), availableLabels(labels, listOf("WEB")).map { it.name })
    }

    @Test
    fun labelsWithTheSameNameCollapseToTheFirstOneSeen() {
        val labels = listOf(label("Bug", "MOB"), label("bug", "WEB"), label("Chore", "MOB"))
        val available = availableLabels(labels, listOf("MOB", "WEB"))

        assertEquals(listOf("Bug", "Chore"), available.map { it.name })
        assertEquals("id-Bug-MOB", available.first().id)
    }

    @Test
    fun labelsSortByNameIgnoringCase() {
        val labels = listOf(label("zeta"), label("Alpha"), label("mid"))
        assertEquals(listOf("Alpha", "mid", "zeta"), availableLabels(labels, emptyList()).map { it.name })
    }

    @Test
    fun reconcileKeepsEverythingStillAvailable() {
        val filters = FilterOptions(team = "MOB,WEB", project = "Launch", label = "bug", excludeLabel = "chore")
        val availables = Availables(teams, projects, listOf(label("bug"), label("chore")))

        assertEquals(filters, reconcile(filters, availables))
    }

    @Test
    fun reconcileClearsAProjectThatIsNoLongerAvailable() {
        val filters = FilterOptions(project = "Launch", projectId = "id-Launch")
        val reconciled = reconcile(filters, Availables(teams, listOf(projects[1]), emptyList()))

        assertNull(reconciled.project)
        assertNull(reconciled.projectId)
    }

    @Test
    fun reconcileClearsLabelsThatAreNoLongerAvailable() {
        val filters = FilterOptions(label = "bug", excludeLabel = "chore")
        val reconciled = reconcile(filters, Availables(teams, projects, listOf(label("chore"))))

        assertNull(reconciled.label)
        assertEquals("chore", reconciled.excludeLabel)
    }

    @Test
    fun reconcileDropsOnlyTheTeamKeysThatWentAway() {
        val filters = FilterOptions(team = "MOB,WEB,OPS")
        val reconciled = reconcile(filters, Availables(listOf(team("MOB"), team("OPS")), projects, emptyList()))

        assertEquals("MOB,OPS", reconciled.team)
    }

    @Test
    fun reconcileClearsTheTeamFilterWhenNoKeySurvives() {
        val filters = FilterOptions(team = "MOB")
        assertNull(reconcile(filters, Availables(listOf(team("OPS")), projects, emptyList())).team)
    }

    @Test
    fun reconcileLeavesAnUnchangedTeamStringExactlyAsItWas() {
        val filters = FilterOptions(team = " MOB , WEB ")
        assertEquals(" MOB , WEB ", reconcile(filters, Availables(teams, projects, emptyList())).team)
    }

    @Test
    fun reconcileKeepsTheProjectAPastedUrlResolvedTo() {
        // The URL resolver sees completed projects; the filter bar's list does not.
        val filters = FilterOptions(project = "Retired", projectId = "p-9")
        val availables = Availables(teams, projects, emptyList())

        assertNull(reconcile(filters, availables).project)

        val kept = reconcile(filters, availables, keepProject = true)
        assertEquals("Retired", kept.project)
        assertEquals("p-9", kept.projectId)
    }
}
