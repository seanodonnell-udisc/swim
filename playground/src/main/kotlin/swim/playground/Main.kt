package swim.playground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import swim.layout.LayoutEdge
import swim.layout.LayoutEdgeKind
import swim.layout.LayoutParams
import swim.layout.LayoutResult
import swim.layout.layout
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private val Background = Color(0xFF14161A)
private val Panel = Color(0xFF1C2027)
private val NodeFill = Color(0xFF262C36)
private val NodeStroke = Color(0xFF3F4A59)
private val Label = Color(0xFFE6EAF0)
private val BlocksLine = Color(0xFF7FB2FF)
private val CrossLine = Color(0xFFFFA23A)
private val CycleLine = Color(0xFFFF5A5A)
private val RelatedLine = Color(0xFF8A93A0)

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "swim layout playground") {
        Playground()
    }
}

@Composable
fun Playground() {
    var graph by remember { mutableStateOf(sampleGraphs.first()) }
    var levelGap by remember { mutableStateOf(80f) }
    var siblingGap by remember { mutableStateOf(40f) }
    var treeGap by remember { mutableStateOf(120f) }
    var affinity by remember { mutableStateOf(0f) }
    var pan by remember { mutableStateOf(Offset(80f, 40f)) }
    var zoom by remember { mutableStateOf(1f) }

    val result = layout(
        nodes = graph.nodes,
        edges = graph.edges,
        params = LayoutParams(levelGap, siblingGap, treeGap, affinity),
    )
    val measurer = rememberTextMeasurer()

    Column(Modifier.fillMaxSize().background(Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Panel).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GraphPicker(graph) { graph = it }
            Knob("level gap ${levelGap.roundToInt()}", levelGap, 0f..300f) { levelGap = it }
            Knob("sibling gap ${siblingGap.roundToInt()}", siblingGap, 0f..200f) { siblingGap = it }
            Knob("tree gap ${treeGap.roundToInt()}", treeGap, 0f..400f) { treeGap = it }
            Knob("affinity ${(affinity * 10f).roundToInt() / 10f}", affinity, 0f..10f) { affinity = it }
        }

        Box(
            modifier = Modifier.fillMaxSize().clipToBounds()
                .pointerInput(Unit) { detectDragGestures { _, drag -> pan += drag } }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.first()
                            val next = (zoom * exp(-change.scrollDelta.y * 0.1f)).coerceIn(0.1f, 4f)
                            pan = change.position - (change.position - pan) * (next / zoom)
                            zoom = next
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer(
                    scaleX = zoom,
                    scaleY = zoom,
                    translationX = pan.x,
                    translationY = pan.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            ) {
                drawGraph(graph, result, measurer)
            }
        }
    }
}

@Composable
private fun GraphPicker(selected: SampleGraph, onSelect: (SampleGraph) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(210.dp)) {
        TextButton(onClick = { open = true }) {
            Text(selected.name, color = Label, fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (graph in sampleGraphs) {
                DropdownMenuItem(onClick = { onSelect(graph); open = false }) {
                    Text(graph.name, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.width(170.dp).padding(horizontal = 8.dp)) {
        Text(label, color = Label, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = BlocksLine, activeTrackColor = BlocksLine),
        )
    }
}

private fun DrawScope.drawGraph(graph: SampleGraph, result: LayoutResult, measurer: TextMeasurer) {
    val rects = graph.nodes.associate { node ->
        val position = result.positions.getValue(node.id)
        node.id to Rect(Offset(position.x, position.y), Size(node.width, node.height))
    }
    val cycles = result.cycleEdges.toSet()
    val crossLinks = result.crossLinks.toSet()

    for (edge in graph.edges) {
        val from = rects[edge.from] ?: continue
        val to = rects[edge.to] ?: continue
        when {
            edge.kind == LayoutEdgeKind.RELATED ->
                drawEdge(from, to, RelatedLine, arrow = false, dash = floatArrayOf(2f, 7f))

            edge in cycles -> drawEdge(from, to, CycleLine, arrow = true, dash = null)
            edge in crossLinks -> drawEdge(from, to, CrossLine, arrow = true, dash = floatArrayOf(12f, 8f))
            else -> drawEdge(from, to, BlocksLine, arrow = true, dash = null)
        }
    }

    for (node in graph.nodes) {
        val rect = rects.getValue(node.id)
        drawRoundRect(NodeFill, rect.topLeft, rect.size, CornerRadius(10f))
        drawRoundRect(NodeStroke, rect.topLeft, rect.size, CornerRadius(10f), Stroke(width = 1.5f))
        val text = measurer.measure(AnnotatedString(node.id), TextStyle(color = Label, fontSize = 13.sp))
        drawText(
            textLayoutResult = text,
            topLeft = Offset(
                rect.left + (rect.width - text.size.width) / 2f,
                rect.top + (rect.height - text.size.height) / 2f,
            ),
        )
    }
}

private fun DrawScope.drawEdge(from: Rect, to: Rect, color: Color, arrow: Boolean, dash: FloatArray?) {
    val (start, end) = anchors(from, to)
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = 1.8f,
        cap = StrokeCap.Round,
        pathEffect = dash?.let { PathEffect.dashPathEffect(it) },
    )
    if (arrow) drawArrowHead(start, end, color)
}

private fun anchors(from: Rect, to: Rect): Pair<Offset, Offset> = when {
    from.bottom <= to.top -> Offset(from.center.x, from.bottom) to Offset(to.center.x, to.top)
    to.bottom <= from.top -> Offset(from.center.x, from.top) to Offset(to.center.x, to.bottom)
    from.center.x <= to.center.x -> Offset(from.right, from.center.y) to Offset(to.left, to.center.y)
    else -> Offset(from.left, from.center.y) to Offset(to.right, to.center.y)
}

private fun DrawScope.drawArrowHead(start: Offset, end: Offset, color: Color) {
    val angle = atan2(end.y - start.y, end.x - start.x)
    val wing = 0.4f
    val length = 9f
    val path = Path().apply {
        moveTo(end.x, end.y)
        lineTo(end.x - length * cos(angle - wing), end.y - length * sin(angle - wing))
        lineTo(end.x - length * cos(angle + wing), end.y - length * sin(angle + wing))
        close()
    }
    drawPath(path, color)
}
