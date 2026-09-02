package swim.layout

data class Point(val x: Double, val y: Double)

data class Size(val width: Double, val height: Double)

data class LayoutResult(val positions: Map<String, Point>)

fun layout(nodes: List<String>, edges: List<Pair<String, String>>): LayoutResult {
    return LayoutResult(positions = emptyMap())
}
