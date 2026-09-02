package swim.layout

// Generated from the shape of a real Linear project graph. Node names are anonymous and
// every card is the constant desktop card size. The BLOCKS edge list keeps the real
// structure exactly, so the tidy-tree compactness test runs against the shape that broke.
// `realShape` is the app default view (completed and canceled issues removed).
// `fullShape` is the same project with every issue.

internal val realShapeNodes: List<LayoutNode> =
    (1..16).map { LayoutNode("N$it", width = 270f, height = 120f) }

internal val realShapeBlocks: List<LayoutEdge> = listOf(
    LayoutEdge("N16", "N3", LayoutEdgeKind.BLOCKS),
    LayoutEdge("N13", "N7", LayoutEdgeKind.BLOCKS),
    LayoutEdge("N16", "N11", LayoutEdgeKind.BLOCKS),
    LayoutEdge("N12", "N13", LayoutEdgeKind.BLOCKS),
    LayoutEdge("N13", "N14", LayoutEdgeKind.BLOCKS),
    LayoutEdge("N16", "N15", LayoutEdgeKind.BLOCKS),
)

internal val realShapeRelated: List<LayoutEdge> = listOf(
    LayoutEdge("N3", "N11", LayoutEdgeKind.RELATED),
)

internal val fullShapeNodes: List<LayoutNode> =
    (1..60).map { LayoutNode("F$it", width = 270f, height = 120f) }

internal val fullShapeBlocks: List<LayoutEdge> = listOf(
    LayoutEdge("F58", "F3", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F14", "F5", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F55", "F5", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F48", "F6", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F50", "F8", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F57", "F13", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F14", "F44", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F57", "F15", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F34", "F16", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F59", "F16", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F22", "F18", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F25", "F18", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F28", "F18", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F33", "F18", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F24", "F18", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F29", "F18", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F30", "F18", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F24", "F19", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F24", "F20", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F24", "F21", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F30", "F22", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F33", "F22", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F30", "F23", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F28", "F24", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F33", "F24", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F29", "F24", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F39", "F25", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F32", "F25", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F37", "F25", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F29", "F25", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F29", "F26", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F38", "F27", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F32", "F27", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F29", "F27", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F32", "F28", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F40", "F28", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F38", "F28", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F35", "F29", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F33", "F30", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F32", "F30", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F36", "F31", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F39", "F32", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F40", "F32", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F41", "F33", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F34", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F41", "F34", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F41", "F35", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F35", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F36", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F37", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F38", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F39", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F40", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F42", "F41", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F58", "F45", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F55", "F45", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F46", "F60", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F47", "F50", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F50", "F51", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F58", "F54", LayoutEdgeKind.BLOCKS),
    LayoutEdge("F57", "F56", LayoutEdgeKind.BLOCKS),
)

internal val fullShapeRelated: List<LayoutEdge> = listOf(
    LayoutEdge("F3", "F45", LayoutEdgeKind.RELATED),
    LayoutEdge("F8", "F48", LayoutEdgeKind.RELATED),
    LayoutEdge("F14", "F35", LayoutEdgeKind.RELATED),
    LayoutEdge("F20", "F42", LayoutEdgeKind.RELATED),
    LayoutEdge("F20", "F32", LayoutEdgeKind.RELATED),
    LayoutEdge("F22", "F24", LayoutEdgeKind.RELATED),
    LayoutEdge("F29", "F32", LayoutEdgeKind.RELATED),
    LayoutEdge("F30", "F41", LayoutEdgeKind.RELATED),
    LayoutEdge("F48", "F31", LayoutEdgeKind.RELATED),
    LayoutEdge("F33", "F34", LayoutEdgeKind.RELATED),
    LayoutEdge("F39", "F41", LayoutEdgeKind.RELATED),
    LayoutEdge("F48", "F49", LayoutEdgeKind.RELATED),
    LayoutEdge("F50", "F48", LayoutEdgeKind.RELATED),
    LayoutEdge("F52", "F48", LayoutEdgeKind.RELATED),
    LayoutEdge("F52", "F49", LayoutEdgeKind.RELATED),
)
