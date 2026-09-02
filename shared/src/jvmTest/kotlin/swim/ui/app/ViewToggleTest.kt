package swim.ui.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HINT = "Connect GitHub to derive relations from PRs"
private val ON_THE_BOX = Offset(60f, 14f)
private val OFF_THE_BOX = Offset(300f, 110f)

/**
 * The view toolbar's "Derive relations from PR stacks" box. Without a GitHub token there are no
 * pull requests to read, so the box must refuse the click and say why.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ViewToggleTest {

    private val log = mutableListOf<Boolean>()

    private fun scene(enabled: Boolean) = ImageComposeScene(340, 130, Density(1f)) {
        SwimCheckbox(
            label = "Derive relations from PR stacks",
            checked = enabled,
            onCheckedChange = { log += it },
            enabled = enabled,
            hint = if (enabled) null else HINT,
        )
    }

    @Test
    fun theToggleIsDeadAndHintedWithoutGithub() {
        val scene = scene(enabled = false)
        try {
            scene.render()
            val idle = pixels(scene)
            click(scene, ON_THE_BOX)
            assertEquals(emptyList(), log, "a disabled toggle reported a change")

            // The pointer is still resting on it, so the hint is up.
            val hinted = pixels(scene)
            assertTrue(
                idle.indices.count { idle[it] != hinted[it] } > 400,
                "the disabled toggle drew no hint on hover",
            )

            // And the hint goes away with the pointer, so it is not just a permanent label.
            scene.sendPointerEvent(PointerEventType.Move, OFF_THE_BOX)
            scene.render()
            assertEquals(idle.toList(), pixels(scene).toList(), "the hint outlived the hover")
        } finally {
            scene.close()
        }
    }

    @Test
    fun theToggleWorksWithGithub() {
        val scene = scene(enabled = true)
        try {
            scene.render()
            click(scene, ON_THE_BOX)
            assertEquals(listOf(false), log, "the enabled toggle did not report the new value")
        } finally {
            scene.close()
        }
    }

    private fun pixels(scene: ImageComposeScene): IntArray {
        val map = scene.render().toComposeImageBitmap().toPixelMap()
        val out = IntArray(map.width * map.height)
        var i = 0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) out[i++] = map[x, y].toArgb()
        }
        return out
    }

    private fun click(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.render()
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
}
