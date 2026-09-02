package swim.ui.graph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val WIDTH = 1400
private const val HEIGHT = 900

@OptIn(ExperimentalComposeUiApi::class)
class GraphCanvasRenderTest {

    @Test
    fun rendersThePreviewGraph() {
        val scene = ImageComposeScene(
            width = WIDTH,
            height = HEIGHT,
            density = Density(1f),
        ) {
            GraphCanvas(
                graph = GraphCanvasPreview.graph,
                positions = GraphCanvasPreview.positions,
                modifier = Modifier.fillMaxSize(),
                readySet = GraphCanvasPreview.readySet,
                prStatuses = GraphCanvasPreview.prStatuses,
                users = GraphCanvasPreview.users,
                crossLinks = GraphCanvasPreview.crossLinks,
                cycleEdges = GraphCanvasPreview.cycleEdges,
                selection = setOf("ENG-104"),
            )
        }

        val pixels = try {
            // The first frame sizes the viewport; the second one lands the fit-to-content it arms.
            scene.render()
            val image = scene.render()
            File("build/reports").mkdirs()
            File("build/reports/graph-canvas.png")
                .writeBytes(requireNotNull(image.encodeToData()).bytes)
            image.toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }

        val painted = count(pixels) { it != Swim.Bg }
        assertTrue(
            painted > WIDTH * HEIGHT / 20,
            "the canvas looks blank: only $painted pixels differ from the background",
        )

        val red = count(pixels) { near(it, Swim.Red) }
        assertTrue(red > 200, "no blocks edges were drawn: $red red pixels")

        val cyan = count(pixels) { near(it, Swim.Cyan) }
        assertTrue(cyan > 50, "no identifiers were drawn: $cyan cyan pixels")

        val minimap = countIn(pixels, WIDTH - 192, HEIGHT - 132, 180, 120) { it != Swim.Bg }
        assertTrue(minimap > 180 * 120 / 2, "the minimap is missing: $minimap painted pixels")
    }

    private fun near(color: Color, target: Color, tolerance: Float = 0.12f): Boolean =
        abs(color.red - target.red) < tolerance &&
            abs(color.green - target.green) < tolerance &&
            abs(color.blue - target.blue) < tolerance

    private fun count(pixels: PixelMap, predicate: (Color) -> Boolean): Int =
        countIn(pixels, 0, 0, pixels.width, pixels.height, predicate)

    private fun countIn(
        pixels: PixelMap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        predicate: (Color) -> Boolean,
    ): Int {
        var hits = 0
        for (y in top until top + height) {
            for (x in left until left + width) {
                if (predicate(pixels[x, y])) hits++
            }
        }
        return hits
    }
}
