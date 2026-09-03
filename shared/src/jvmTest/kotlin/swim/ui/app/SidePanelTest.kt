package swim.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import swim.core.model.FilterOptions
import swim.core.model.IssueNode
import swim.core.model.UserSummary
import swim.core.model.WorkflowStateType
import swim.core.session.Availables
import swim.core.session.FilterStore
import swim.ui.filters.FiltersDialog
import swim.ui.graph.Swim
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WIDTH = 520
private const val HEIGHT = 640

/** Inside the panel header's one button, which is the collapse toggle at either width. */
private val TOGGLE = Offset(15f, 24f)

private fun issue(id: String) = IssueNode(
    id = id,
    identifier = id,
    title = "The title of $id, long enough to wrap onto a second line in the panel",
    state = "Todo",
    stateType = WorkflowStateType.UNSTARTED,
    priority = 0,
    team = "ENG",
)

/**
 * The panel and the filters modal, driven by a synthetic pointer. Targets are found by colour
 * rather than by arithmetic over the layout, so a spacing change does not silently aim these
 * clicks at empty chrome.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SidePanelTest {

    private val scenes = mutableListOf<ImageComposeScene>()

    @AfterTest
    fun tearDown() = scenes.forEach { it.close() }

    private fun scene(width: Int = WIDTH, content: @Composable () -> Unit): ImageComposeScene =
        ImageComposeScene(width, HEIGHT, Density(1f), content = content).also { scenes += it }

    @Test
    fun theToggleFoldsThePanelToARailAndBackAgain() {
        var collapsed by mutableStateOf(false)
        var bodies = 0
        val scene = scene {
            SidePanel(collapsed = collapsed, onToggle = { collapsed = !collapsed }) {
                bodies++
                Text("view controls", color = Swim.Text)
            }
        }
        scene.render()
        assertTrue(bodies > 0, "the expanded panel never composed its body")

        click(scene, TOGGLE)
        assertTrue(collapsed, "the toggle did not collapse the panel")
        val afterCollapse = bodies
        scene.render()
        assertEquals(afterCollapse, bodies, "the rail still composed the panel body")

        // The rail is narrower than the body, so everything right of it is now bare canvas.
        assertEquals(
            Color.Transparent,
            pixels(scene)[PANEL_RAIL.value.toInt() + 40, HEIGHT / 2],
            "the collapsed rail still painted the panel width",
        )

        click(scene, TOGGLE)
        assertTrue(!collapsed, "the toggle did not expand the panel again")
        scene.render()
        assertTrue(bodies > afterCollapse, "the expanded panel did not compose its body again")
    }

    @Test
    fun theSelectionSectionOnlyAppearsWhileSomethingIsSelected() {
        var selection by mutableStateOf(emptySet<String>())
        val scene = scene {
            SidePanel(collapsed = false, onToggle = {}) {
                SelectionSection(
                    selection = selection,
                    nodes = listOf(issue("ENG-101")),
                    users = listOf(UserSummary("u1", "Kim")),
                    onOpenIssue = {},
                    onCopyId = {},
                    onAssign = { _, _ -> },
                    onRemoveFromProject = {},
                    onClear = {},
                )
            }
        }
        scene.render()
        val empty = pixels(scene)

        selection = setOf("ENG-101")
        scene.render()
        val selected = pixels(scene)

        assertTrue(
            differences(empty, selected) > 500,
            "selecting a card drew no selection section",
        )
        // The identifier is the one cyan thing in the panel, and it is only there when selected.
        assertEquals(0, count(empty, Swim.Cyan))
        assertTrue(count(selected, Swim.Cyan) > 0, "the selection section named no issue")
    }

    @Test
    fun theModalAppliesTheFiltersItWasOpenedOn() {
        val store = FilterStore(FakeSettings())
        store.setTeam("ENG")
        val scene = filtersScene(store)
        scene.render()

        assertTrue(!store.state.value.shouldLoadIssues, "an unopened modal already loaded")
        click(scene, applyButton(scene))

        assertTrue(store.state.value.shouldLoadIssues, "Apply did not load the filters")
        assertEquals(FilterOptions(team = "ENG"), store.filters, "Apply changed the query")
    }

    @Test
    fun theModalClearsEveryFilterAndDisarmsTheLoad() {
        val store = FilterStore(FakeSettings())
        store.setTeam("ENG")
        store.setLabel("bug")
        store.applyFilters()
        val scene = filtersScene(store)
        scene.render()

        // Clear sits on the same row as Apply, first of the three.
        click(scene, buttonRow(scene).first())

        assertEquals(FilterOptions(), store.filters, "Clear left a filter set")
        assertTrue(!store.state.value.shouldLoadIssues, "Clear left the load armed")
    }

    private fun filtersScene(store: FilterStore) = scene {
        Box(Modifier.fillMaxSize()) {
            FiltersDialog(
                filters = store.state.value.filters,
                availables = Availables(),
                store = store,
                onApply = store::applyFilters,
                onDismiss = {},
            )
        }
    }

    /** Apply is the only primary button in the modal, so its accent fill names it outright. */
    private fun applyButton(scene: ImageComposeScene): Offset =
        assertNotNull(centreOf(pixels(scene), Swim.Active), "the modal drew no Apply button")

    /**
     * The three buttons on the modal's last row, left to right. Apply gives the row its y; the
     * quiet buttons on that row are the only [Swim.CardHover] runs across it.
     */
    private fun buttonRow(scene: ImageComposeScene): List<Offset> {
        val map = pixels(scene)
        val y = applyButton(scene).y.toInt()
        val quiet = Swim.CardHover
        val runs = mutableListOf<IntRange>()
        var start: Int? = null
        for (x in 0 until map.width) {
            val hit = map[x, y] == quiet
            if (hit && start == null) start = x
            if (!hit && start != null) {
                runs += start..(x - 1)
                start = null
            }
        }
        start?.let { runs += it until map.width }
        return runs.filter { it.last - it.first > 8 }
            .map { Offset((it.first + it.last) / 2f, y.toFloat()) }
    }
}

private fun count(map: PixelMap, color: Color): Int {
    var hits = 0
    for (y in 0 until map.height) {
        for (x in 0 until map.width) if (map[x, y] == color) hits++
    }
    return hits
}

private fun centreOf(map: PixelMap, color: Color): Offset? {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (y in 0 until map.height) {
        for (x in 0 until map.width) {
            if (map[x, y] != color) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    if (minX > maxX) return null
    return Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
}

private fun differences(a: PixelMap, b: PixelMap): Int {
    var differing = 0
    for (y in 0 until a.height) {
        for (x in 0 until a.width) if (a[x, y] != b[x, y]) differing++
    }
    return differing
}

@OptIn(ExperimentalComposeUiApi::class)
private fun pixels(scene: ImageComposeScene) =
    scene.render().toComposeImageBitmap().toPixelMap()

@OptIn(ExperimentalComposeUiApi::class)
private fun click(scene: ImageComposeScene, at: Offset) {
    scene.sendPointerEvent(PointerEventType.Move, at)
    scene.sendPointerEvent(
        PointerEventType.Press, at,
        button = PointerButton.Primary,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    scene.sendPointerEvent(
        PointerEventType.Release, at,
        button = PointerButton.Primary,
        buttons = PointerButtons(),
    )
    scene.render()
}
