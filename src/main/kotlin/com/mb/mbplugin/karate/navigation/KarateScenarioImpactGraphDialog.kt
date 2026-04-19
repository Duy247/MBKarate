package com.mb.mbplugin.karate.navigation

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.ScrollPaneConstants
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ScenarioImpactGraphModel(
    val rootNodeId: String,
    val nodes: List<ScenarioImpactGraphNode>,
    val edges: List<ScenarioImpactGraphEdge>
)

data class ScenarioImpactGraphNode(
    val id: String,
    val file: com.intellij.openapi.vfs.VirtualFile,
    val tag: String,
    val scenarioName: String?,
    val level: Int,
    val navigateOffset: Int
)

data class ScenarioImpactGraphEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val label: String,
    val usageCount: Int = 1
)

class KarateScenarioImpactGraphDialog(
    private val project: Project,
    private val model: ScenarioImpactGraphModel
) : DialogWrapper(project) {

    init {
        title = "Scenario Impact Graph"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val graphPanel = ScenarioImpactGraphPanel(model) { node ->
            OpenFileDescriptor(project, node.file, node.navigateOffset).navigate(true)
        }
        val scrollPane = JBScrollPane(graphPanel).apply {
            preferredSize = Dimension(1200, 760)
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            hideScrollbar(horizontalScrollBar)
            hideScrollbar(verticalScrollBar)
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            add(JBLabel("Tip: wheel=zoom, drag with left mouse=pan, labels simplify when zoomed out"))
        }

        val root = JPanel(BorderLayout())
        root.add(toolbar, BorderLayout.NORTH)
        root.add(scrollPane, BorderLayout.CENTER)

        SwingUtilities.invokeLater {
            graphPanel.resetView(scrollPane.viewport)
        }

        return root
    }

    private fun hideScrollbar(scrollBar: JScrollBar?) {
        if (scrollBar == null) return
        scrollBar.isOpaque = false
        scrollBar.preferredSize = Dimension(2, 2)
        scrollBar.unitIncrement = 16
        scrollBar.blockIncrement = 96
    }
}

private class ScenarioImpactGraphPanel(
    private val model: ScenarioImpactGraphModel,
    private val onNodeClick: (ScenarioImpactGraphNode) -> Unit
) : JPanel() {

    private val nodeWidth = 250
    private val nodeHeight = 54
    private val subColumnGap = 80
    private val verticalGap = 24
    private val islandGap = 150
    private val margin = 48
    private val maxRowsPerStack = 18
    private val canvasPadding = 1600

    private val minZoom = 0.2
    private val maxZoom = 3.0
    private var zoom = 1.0

    private val nodeBounds = linkedMapOf<String, Rectangle>()
    private val islandLayoutsByLevel = linkedMapOf<Int, List<IslandLayout>>()
    private val nodesById = model.nodes.associateBy { it.id }

    private val outgoing = mutableMapOf<String, MutableList<Int>>()
    private val incoming = mutableMapOf<String, MutableList<Int>>()

    private var hoveredNodeId: String? = null
    private var highlightedEdgeIndexes: Set<Int> = emptySet()
    private var highlightedNodeIds: Set<String> = emptySet()
    private var contentBounds: Rectangle = Rectangle()

    private val baseSize = computeBaseLayout()

    private var panning = false
    private var panMoved = false
    private var panLastScreenPoint = Point()

    init {
        background = JBColor.PanelBackground
        toolTipText = "impact"
        autoscrolls = false

        model.edges.forEachIndexed { index, edge ->
            outgoing.computeIfAbsent(edge.fromNodeId) { mutableListOf() }.add(index)
            incoming.computeIfAbsent(edge.toNodeId) { mutableListOf() }.add(index)
        }

        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                e.consume()
                panning = true
                panMoved = false
                panLastScreenPoint = e.locationOnScreen
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    panning = false
                    cursor = if (hoveredNodeId != null) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                    e.consume()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!panning) return
                val vp = viewport() ?: return
                val currentScreenPoint = e.locationOnScreen
                val dx = currentScreenPoint.x - panLastScreenPoint.x
                val dy = currentScreenPoint.y - panLastScreenPoint.y
                if (abs(dx) > 2 || abs(dy) > 2) panMoved = true

                val current = vp.viewPosition
                val newX = current.x - dx
                val newY = current.y - dy
                vp.viewPosition = clampViewPosition(vp, Point(newX, newY))
                panLastScreenPoint = currentScreenPoint
                e.consume()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || panMoved) return
                val modelPoint = toModelPoint(e.point)
                val id = nodeBounds.entries.firstOrNull { it.value.contains(modelPoint) }?.key ?: return
                val node = nodesById[id] ?: return
                onNodeClick(node)
            }

            override fun mouseMoved(e: MouseEvent) {
                if (panning) return
                val modelPoint = toModelPoint(e.point)
                val nodeId = nodeBounds.entries.firstOrNull { it.value.contains(modelPoint) }?.key

                if (nodeId != hoveredNodeId) {
                    hoveredNodeId = nodeId
                    recomputeHighlights(nodeId)
                    repaint()
                }

                cursor = if (hoveredNodeId != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }

            override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent) {
                e.consume()
                val factor = if (e.preciseWheelRotation > 0) 0.9 else 1.1
                val vp = viewport()
                val anchorInView = if (vp != null) {
                    Point(e.x - vp.viewPosition.x, e.y - vp.viewPosition.y)
                } else {
                    e.point
                }
                zoomBy(factor, anchorInView)
            }
        }

        addMouseListener(mouseAdapter)
        addMouseMotionListener(mouseAdapter)
        addMouseWheelListener(mouseAdapter)
    }

    fun getZoom(): Double = zoom

    fun zoomBy(factor: Double, anchorInView: Point? = null) {
        val vp = viewport()
        val oldZoom = zoom
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        if (abs(newZoom - oldZoom) < 0.0001) return

        val anchor = anchorInView ?: vp?.let { Point(it.extentSize.width / 2, it.extentSize.height / 2) } ?: Point(0, 0)
        val currentView = vp?.viewPosition ?: Point(0, 0)

        val anchorModelX = (currentView.x + anchor.x) / oldZoom
        val anchorModelY = (currentView.y + anchor.y) / oldZoom

        zoom = newZoom
        revalidate()
        repaint()

        if (vp != null) {
            val newViewX = (anchorModelX * newZoom - anchor.x).roundToInt()
            val newViewY = (anchorModelY * newZoom - anchor.y).roundToInt()
            vp.viewPosition = clampViewPosition(vp, Point(newViewX, newViewY))
        }
    }

    fun fitToViewport(viewportSize: Dimension) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) {
            return
        }
        val fitTargetWidth = max(1, contentBounds.width + 80)
        val fitTargetHeight = max(1, contentBounds.height + 80)
        val fitX = (viewportSize.width - 24).toDouble() / fitTargetWidth
        val fitY = (viewportSize.height - 24).toDouble() / fitTargetHeight
        zoom = min(fitX, fitY).coerceIn(minZoom, maxZoom)
        revalidate()
        repaint()
    }

    fun resetView(vp: JViewport?) {
        val viewport = vp ?: return
        zoom = when {
            model.nodes.size <= 12 -> 1.0
            model.nodes.size <= 40 -> 0.85
            else -> 0.7
        }.coerceIn(minZoom, maxZoom)
        revalidate()
        repaint()

        val rootRect = nodeBounds[model.rootNodeId] ?: run {
            viewport.viewPosition = Point(0, 0)
            return
        }

        val targetX = (rootRect.centerX * zoom - viewport.extentSize.width / 2.0).roundToInt()
        val targetY = (rootRect.centerY * zoom - viewport.extentSize.height / 2.0).roundToInt()
        viewport.viewPosition = clampViewPosition(viewport, Point(targetX, targetY))
    }

    override fun getPreferredSize(): Dimension {
        val width = max(1, (baseSize.width * zoom).roundToInt())
        val height = max(1, (baseSize.height * zoom).roundToInt())
        val extent = viewport()?.extentSize
        return Dimension(
            max(width, extent?.width ?: 0),
            max(height, extent?.height ?: 0)
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2Scaled = g.create() as Graphics2D
        g2Scaled.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2Scaled.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2Scaled.scale(zoom, zoom)

        drawIslands(g2Scaled)
        drawEdges(g2Scaled)
        drawNodes(g2Scaled)
        g2Scaled.dispose()

        val g2Overlay = g.create() as Graphics2D
        g2Overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2Overlay.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        drawFixedOverlayLabels(g2Overlay)
        g2Overlay.dispose()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val point = toModelPoint(event.point)
        val id = nodeBounds.entries.firstOrNull { it.value.contains(point) }?.key ?: return null
        val node = nodesById[id] ?: return null
        val scenario = node.scenarioName?.takeIf { it.isNotBlank() } ?: "Unnamed scenario"
        return "${node.file.name}@${node.tag} - $scenario"
    }

    private fun computeBaseLayout(): Dimension {
        nodeBounds.clear()
        islandLayoutsByLevel.clear()

        val rootRectRaw = Rectangle(-nodeWidth / 2, -nodeHeight / 2, nodeWidth, nodeHeight)

        val islands = model.nodes
            .asSequence()
            .filter { it.id != model.rootNodeId }
            .groupBy { node -> IslandBucket(level = node.level, path = islandKey(node.file.path)) }
            .toList()
            .sortedWith(compareBy<Pair<IslandBucket, List<ScenarioImpactGraphNode>>>({ it.first.level }, { it.first.path }))
            .map { (bucket, islandNodes) ->
                val sortedNodes = islandNodes.sortedWith(
                    compareBy<ScenarioImpactGraphNode>({ it.level }, { it.file.path }, { it.tag })
                )
                val stacks = sortedNodes.chunked(maxRowsPerStack)
                val maxRows = stacks.maxOfOrNull { it.size } ?: 1
                val width = stacks.size * nodeWidth + max(0, stacks.size - 1) * subColumnGap
                val height = maxRows * nodeHeight + max(0, maxRows - 1) * verticalGap
                IslandLayout(
                    key = bucket.path,
                    nodes = sortedNodes,
                    stacks = stacks,
                    level = bucket.level,
                    rect = Rectangle(0, 0, width, height)
                )
            }
            .sortedWith(
                compareBy<IslandLayout> { it.level }
                    .thenByDescending { it.rect.width * it.rect.height }
                    .thenBy { it.key }
            )

        val placedIslands = placeIslandsAroundRoot(islands, rootRectRaw)

        val rawRects = mutableListOf(rootRectRaw)
        rawRects.addAll(placedIslands.map { it.rect })

        val minX = rawRects.minOfOrNull { it.x } ?: 0
        val minY = rawRects.minOfOrNull { it.y } ?: 0
        val offsetX = margin - minX
        val offsetY = margin - minY

        val rootRect = shiftRect(rootRectRaw, offsetX, offsetY)
        nodeBounds[model.rootNodeId] = rootRect

        placedIslands.forEach { island ->
            island.rect = shiftRect(island.rect, offsetX, offsetY)
            island.stacks.forEachIndexed { stackIndex, stack ->
                val x = island.rect.x + stackIndex * (nodeWidth + subColumnGap)
                stack.forEachIndexed { rowIndex, node ->
                    val y = island.rect.y + rowIndex * (nodeHeight + verticalGap)
                    nodeBounds[node.id] = Rectangle(x, y, nodeWidth, nodeHeight)
                }
            }
        }

        nodeBounds.replaceAll { _, rect -> shiftRect(rect, canvasPadding, canvasPadding) }
        placedIslands.forEach { island ->
            island.rect = shiftRect(island.rect, canvasPadding, canvasPadding)
        }

        islandLayoutsByLevel[0] = placedIslands.sortedBy { rectAngle(it.rect) }
        contentBounds = computeContentBounds()

        val maxX = nodeBounds.values.maxOfOrNull { it.x + it.width } ?: (margin * 2 + nodeWidth + canvasPadding)
        val maxY = nodeBounds.values.maxOfOrNull { it.y + it.height } ?: (margin * 2 + nodeHeight + canvasPadding)
        return Dimension(maxX + margin + canvasPadding, maxY + margin + canvasPadding)
    }

    private fun computeContentBounds(): Rectangle {
        if (nodeBounds.isEmpty()) return Rectangle(0, 0, 1, 1)
        val minX = nodeBounds.values.minOf { it.x }
        val minY = nodeBounds.values.minOf { it.y }
        val maxX = nodeBounds.values.maxOf { it.x + it.width }
        val maxY = nodeBounds.values.maxOf { it.y + it.height }
        return Rectangle(minX, minY, max(1, maxX - minX), max(1, maxY - minY))
    }

    private fun placeIslandsAroundRoot(
        islands: List<IslandLayout>,
        rootRect: Rectangle
    ): List<IslandLayout> {
        val occupied = mutableListOf<Rectangle>()
        occupied += expandRect(rootRect, islandGap / 2, islandGap / 3)
        val placedIslandByNodeId = mutableMapOf<String, IslandLayout>()

        val levels = islands.groupBy { it.level }.toSortedMap()
        var levelBaseRadius = max(rootRect.width, rootRect.height) / 2f + 120f
        var previousLevelOuterRadius = levelBaseRadius

        for ((_, levelIslands) in levels) {
            var levelOuterRadius = previousLevelOuterRadius

            val orderedLevelIslands = levelIslands.sortedWith(
                compareByDescending<IslandLayout> { countLinksToPlaced(it, placedIslandByNodeId) }
                    .thenByDescending { it.rect.width * it.rect.height }
                    .thenBy { it.key }
            )

            orderedLevelIslands.forEachIndexed { index, island ->
                val desiredAngle = computeDesiredAngle(island, placedIslandByNodeId)
                val placedRect = findPlacementAroundRoot(
                    width = island.rect.width,
                    height = island.rect.height,
                    occupied = occupied,
                    seed = index,
                    baseRadius = levelBaseRadius,
                    desiredAngle = desiredAngle
                )
                island.rect = placedRect
                occupied += expandRect(placedRect, islandGap / 2, islandGap / 3)
                levelOuterRadius = max(levelOuterRadius, rectOuterRadius(placedRect))
                island.nodes.forEach { node -> placedIslandByNodeId[node.id] = island }
            }

            previousLevelOuterRadius = max(previousLevelOuterRadius, levelOuterRadius)
            levelBaseRadius = previousLevelOuterRadius + 180f
        }

        return islands
    }

    private fun findPlacementAroundRoot(
        width: Int,
        height: Int,
        occupied: List<Rectangle>,
        seed: Int,
        baseRadius: Float,
        desiredAngle: Double?
    ): Rectangle {
        val ringStep = max(120f, min(width, height).toFloat() * 0.45f)
        val targetArc = max(160f, width * 0.9f + islandGap * 0.25f)

        var lastRadius = baseRadius
        repeat(120) {
            val radius = baseRadius + it * ringStep
            lastRadius = radius
            val slots = max(10, ((2.0 * Math.PI * radius) / targetArc).roundToInt())
            val angleStep = (2.0 * Math.PI / slots)
            val offset = (seed % slots) * angleStep * 0.35 + (it % 2) * (angleStep / 2.0)
            var best: Rectangle? = null
            var bestScore = Double.MAX_VALUE

            for (slot in 0 until slots) {
                val angle = offset + slot * angleStep
                val centerX = (radius * cos(angle)).roundToInt()
                val centerY = (radius * sin(angle)).roundToInt()
                val candidate = Rectangle(centerX - width / 2, centerY - height / 2, width, height)
                if (occupied.any { it.intersects(candidate) }) continue

                val radialScore = abs(centerY) * 0.12 + radius.toDouble()
                val angleScore = desiredAngle?.let {
                    angleDistance(it, angle) * radius * 0.35
                } ?: 0.0
                val score = radialScore + angleScore
                if (score < bestScore) {
                    best = candidate
                    bestScore = score
                }
            }

            if (best != null) return best
        }

        // Fallback: guaranteed non-overlap via outward spiral search.
        var fallbackRadius = lastRadius
        var angle = 0.0
        repeat(3000) {
            val centerX = (fallbackRadius * cos(angle)).roundToInt()
            val centerY = (fallbackRadius * sin(angle)).roundToInt()
            val candidate = Rectangle(centerX - width / 2, centerY - height / 2, width, height)
            if (occupied.none { it.intersects(candidate) }) return candidate
            angle += 0.22
            fallbackRadius += 1.8f
        }

        return Rectangle(0, 0, width, height)
    }

    private fun rectOuterRadius(rect: Rectangle): Float {
        val cx = rect.centerX.toFloat()
        val cy = rect.centerY.toFloat()
        val halfDiagonal = sqrt(
            (rect.width * rect.width + rect.height * rect.height).toFloat()
        ) / 2f
        return sqrt(cx * cx + cy * cy) + halfDiagonal
    }

    private fun countLinksToPlaced(
        island: IslandLayout,
        placedIslandByNodeId: Map<String, IslandLayout>
    ): Int {
        if (placedIslandByNodeId.isEmpty()) return 0
        val nodeIds = island.nodes.asSequence().map { it.id }.toHashSet()
        var links = 0
        for (edge in model.edges) {
            if (edge.fromNodeId in nodeIds && placedIslandByNodeId.containsKey(edge.toNodeId)) links++
            if (edge.toNodeId in nodeIds && placedIslandByNodeId.containsKey(edge.fromNodeId)) links++
        }
        return links
    }

    private fun computeDesiredAngle(
        island: IslandLayout,
        placedIslandByNodeId: Map<String, IslandLayout>
    ): Double? {
        if (placedIslandByNodeId.isEmpty()) return null
        val nodeIds = island.nodes.asSequence().map { it.id }.toHashSet()
        var sumX = 0.0
        var sumY = 0.0
        var count = 0

        for (edge in model.edges) {
            val linkedPlacedIsland = when {
                edge.fromNodeId in nodeIds -> placedIslandByNodeId[edge.toNodeId]
                edge.toNodeId in nodeIds -> placedIslandByNodeId[edge.fromNodeId]
                else -> null
            } ?: continue

            val center = linkedPlacedIsland.rect
            sumX += center.centerX
            sumY += center.centerY
            count++
        }

        if (count == 0) return null
        return kotlin.math.atan2(sumY, sumX)
    }

    private fun angleDistance(a: Double, b: Double): Double {
        var d = abs(a - b)
        while (d > Math.PI) d = abs(d - 2.0 * Math.PI)
        return d
    }

    private fun shiftRect(rect: Rectangle, dx: Int, dy: Int): Rectangle =
        Rectangle(rect.x + dx, rect.y + dy, rect.width, rect.height)

    private fun expandRect(rect: Rectangle, padX: Int, padY: Int): Rectangle =
        Rectangle(rect.x - padX, rect.y - padY, rect.width + padX * 2, rect.height + padY * 2)

    private fun rectAngle(rect: Rectangle): Double =
        kotlin.math.atan2(rect.centerY.toDouble(), rect.centerX.toDouble())

    private fun drawIslands(g2: Graphics2D) {
        for ((_, islands) in islandLayoutsByLevel) {
            islands.forEach { island ->
                val r = island.rect
                if (r.width <= 0 || r.height <= 0) return@forEach

                val paddingX = 18
                val paddingY = 14
                val box = Rectangle(
                    r.x - paddingX,
                    r.y - paddingY,
                    r.width + paddingX * 2,
                    r.height + paddingY * 2
                )

                g2.color = JBColor(Color(0x1F2731), Color(0x222A33))
                g2.fillRoundRect(box.x, box.y, box.width, box.height, 14, 14)
                g2.color = JBColor(Color(0x303C4A), Color(0x384454))
                g2.drawRoundRect(box.x, box.y, box.width, box.height, 14, 14)

                if (zoom >= 0.75) {
                    g2.font = g2.font.deriveFont(Font.PLAIN, 10f)
                    g2.color = JBColor(Color(0x6A7B8E), Color(0x8A97A6))
                    // Keep island label outside and above the island container.
                    g2.drawString(trimText(island.key, 30), box.x + 8, box.y - 4)
                }
            }
        }
    }

    private fun drawEdges(g2: Graphics2D) {
        if (hoveredNodeId == null || highlightedEdgeIndexes.isEmpty()) return

        val sideLaneByNodeAndSide = mutableMapOf<String, Int>()

        model.edges.forEachIndexed { index, edge ->
            val fromNode = nodesById[edge.fromNodeId] ?: return@forEachIndexed
            val toNode = nodesById[edge.toNodeId] ?: return@forEachIndexed
            val fromRect = nodeBounds[fromNode.id] ?: return@forEachIndexed
            val toRect = nodeBounds[toNode.id] ?: return@forEachIndexed

            val points = buildEdgePoints(
                edge = edge,
                fromRect = fromRect,
                toRect = toRect,
                sideLaneByNodeAndSide = sideLaneByNodeAndSide
            )

            val highlighted = highlightedEdgeIndexes.contains(index)
            if (!highlighted) return@forEachIndexed
            g2.stroke = BasicStroke(2.0f)
            g2.color = JBColor(Color(0xF5D76E), Color(0xF5D76E))

            drawSmoothPolyline(g2, points)
            drawArrow(g2, points[points.size - 2], points.last(), highlighted)
            if (edge.usageCount > 1) {
                drawEdgeCountBadge(g2, points, edge.usageCount)
            }
        }
    }

    private fun buildEdgePoints(
        edge: ScenarioImpactGraphEdge,
        fromRect: Rectangle,
        toRect: Rectangle,
        sideLaneByNodeAndSide: MutableMap<String, Int>
    ): List<Point2D.Float> {
        val fromLeft = fromRect.x.toFloat()
        val fromRight = (fromRect.x + fromRect.width).toFloat()
        val fromY = fromRect.y + fromRect.height / 2f
        val toLeft = toRect.x.toFloat()
        val toRight = (toRect.x + toRect.width).toFloat()
        val toY = toRect.y + toRect.height / 2f

        val movingLeft = fromRect.centerX > toRect.centerX
        val startAnchorX = if (movingLeft) fromLeft else fromRight
        val endAnchorX = if (movingLeft) toRight else toLeft
        val fromSide = if (movingLeft) "L" else "R"
        val toSide = if (movingLeft) "R" else "L"

        val fromLaneKey = "${edge.fromNodeId}:$fromSide"
        val fromLaneIndex = sideLaneByNodeAndSide.getOrDefault(fromLaneKey, 0).also {
            sideLaneByNodeAndSide[fromLaneKey] = it + 1
        }
        val fromSpread = fromLaneIndex % 4
        val fromOuterX = if (movingLeft) {
            startAnchorX - (10f + fromSpread * 3f)
        } else {
            startAnchorX + (10f + fromSpread * 3f)
        }

        val toLaneKey = "${edge.toNodeId}:$toSide"
        val toLaneIndex = sideLaneByNodeAndSide.getOrDefault(toLaneKey, 0).also {
            sideLaneByNodeAndSide[toLaneKey] = it + 1
        }
        val toSpread = toLaneIndex % 4
        val toOuterX = if (movingLeft) {
            endAnchorX + (10f + toSpread * 3f)
        } else {
            endAnchorX - (10f + toSpread * 3f)
        }

        val horizontalPreferred = abs(toY - fromY) <= 40f || abs(toOuterX - fromOuterX) >= abs(toY - fromY) * 0.6f
        val points = if (horizontalPreferred) {
            listOf(
                Point2D.Float(startAnchorX, fromY),
                Point2D.Float(fromOuterX, fromY),
                Point2D.Float(toOuterX, fromY),
                Point2D.Float(toOuterX, toY),
                Point2D.Float(endAnchorX, toY)
            )
        } else {
            val midY = (fromY + toY) / 2f
            listOf(
                Point2D.Float(startAnchorX, fromY),
                Point2D.Float(fromOuterX, fromY),
                Point2D.Float(fromOuterX, midY),
                Point2D.Float(toOuterX, midY),
                Point2D.Float(toOuterX, toY),
                Point2D.Float(endAnchorX, toY)
            )
        }

        return compactPolylinePoints(points)
    }

    private fun drawSmoothPolyline(g2: Graphics2D, points: List<Point2D.Float>) {
        if (points.size < 2) return

        val path = Path2D.Float()
        path.moveTo(points[0].x.toDouble(), points[0].y.toDouble())

        if (points.size == 2) {
            path.lineTo(points[1].x.toDouble(), points[1].y.toDouble())
            g2.draw(path)
            return
        }

        val cornerRadius = 10f
        for (i in 1 until points.size - 1) {
            val p0 = points[i - 1]
            val p1 = points[i]
            val p2 = points[i + 1]

            val d1 = distance(p0, p1)
            val d2 = distance(p1, p2)
            val radius = min(cornerRadius, min(d1, d2) / 2f)

            val inPoint = moveToward(p1, p0, radius)
            val outPoint = moveToward(p1, p2, radius)

            path.lineTo(inPoint.x.toDouble(), inPoint.y.toDouble())
            path.quadTo(
                p1.x.toDouble(),
                p1.y.toDouble(),
                outPoint.x.toDouble(),
                outPoint.y.toDouble()
            )
        }

        val last = points.last()
        path.lineTo(last.x.toDouble(), last.y.toDouble())
        g2.draw(path)
    }

    private fun drawArrow(g2: Graphics2D, from: Point2D.Float, to: Point2D.Float, highlighted: Boolean) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.01f) return

        val ux = dx / len
        val uy = dy / len
        // Keep arrowhead screen size stable across zoom levels.
        val baseScreenSize = if (highlighted) 12f else 8f
        val size = baseScreenSize / zoom.toFloat().coerceAtLeast(0.001f)

        val tip = Point2D.Float(to.x, to.y)
        val baseX = to.x - ux * size
        val baseY = to.y - uy * size
        val orthoX = -uy * size * 0.5f
        val orthoY = ux * size * 0.5f

        val left = Point2D.Float(baseX + orthoX, baseY + orthoY)
        val right = Point2D.Float(baseX - orthoX, baseY - orthoY)

        val arrow = Path2D.Float()
        arrow.moveTo(tip.x.toDouble(), tip.y.toDouble())
        arrow.lineTo(left.x.toDouble(), left.y.toDouble())
        arrow.lineTo(right.x.toDouble(), right.y.toDouble())
        arrow.closePath()
        g2.fill(arrow)
    }

    private fun drawEdgeCountBadge(g2: Graphics2D, points: List<Point2D.Float>, count: Int) {
        if (points.isEmpty()) return
        val label = "x$count"
        val mid = points[points.size / 2]

        val oldFont = g2.font
        g2.font = g2.font.deriveFont(Font.BOLD, 10f)
        val fm = g2.fontMetrics
        val textWidth = fm.stringWidth(label)
        val badgeWidth = textWidth + 10
        val badgeHeight = fm.height + 2
        val x = (mid.x - badgeWidth / 2f).roundToInt()
        val y = (mid.y - badgeHeight / 2f).roundToInt()

        g2.color = JBColor(Color(0x2D3540), Color(0x2A313B))
        g2.fillRoundRect(x, y, badgeWidth, badgeHeight, 10, 10)
        g2.color = JBColor(Color(0xF5D76E), Color(0xF5D76E))
        g2.drawRoundRect(x, y, badgeWidth, badgeHeight, 10, 10)
        g2.drawString(label, x + 5, y + fm.ascent)
        g2.font = oldFont
    }

    private fun drawFixedOverlayLabels(g2: Graphics2D) {
        if (zoom >= 0.75) return

        val overlayFont = g2.font.deriveFont(Font.BOLD, 12f)
        g2.font = overlayFont

        if (zoom < 0.75) {
            islandLayoutsByLevel.values.flatten().forEach { island ->
                val vx = (island.rect.x * zoom).roundToInt() + 8
                val vy = (island.rect.y * zoom).roundToInt() - 6
                drawOverlayText(
                    g2 = g2,
                    text = trimText(island.key, 42),
                    x = vx,
                    y = vy,
                    fg = JBColor(Color(0x8AA0B8), Color(0x9AA7B6))
                )
            }
        }

        if (zoom >= 0.45) return

        val rootNode = nodesById[model.rootNodeId]
        val rootRect = nodeBounds[model.rootNodeId]
        if (rootNode != null && rootRect != null) {
            val vx = (rootRect.x * zoom).roundToInt() + 8
            val vy = (rootRect.y * zoom).roundToInt() - 8
            drawOverlayText(
                g2 = g2,
                text = trimText("${rootNode.file.name}@${rootNode.tag}", 42),
                x = vx,
                y = vy,
                fg = JBColor(Color(0xE6F7E6), Color(0xE6F7E6))
            )
        }

        val highlightNodeIds = buildSet<String> {
            hoveredNodeId?.let { add(it) }
            if (zoom <= 0.55) addAll(highlightedNodeIds)
        }
        highlightNodeIds.forEach { id ->
            if (id == model.rootNodeId) return@forEach
            val node = nodesById[id] ?: return@forEach
            val rect = nodeBounds[id] ?: return@forEach
            val vx = (rect.x * zoom).roundToInt() + 8
            val vy = (rect.y * zoom).roundToInt() - 8
            drawOverlayText(
                g2 = g2,
                text = trimText("${node.file.name}@${node.tag}", 42),
                x = vx,
                y = vy,
                fg = JBColor(Color(0xFFF6D5), Color(0xFFF6D5))
            )
        }
    }

    private fun drawOverlayText(
        g2: Graphics2D,
        text: String,
        x: Int,
        y: Int,
        fg: Color
    ) {
        if (text.isBlank()) return
        val py = y
        // subtle shadow for readability without adding a new label container
        g2.color = JBColor(Color(0x1A1F26), Color(0x1A1F26))
        g2.drawString(text, x + 1, py + 1)
        g2.color = fg
        g2.drawString(text, x, py)
    }

    private fun drawNodes(g2: Graphics2D) {
        val showTitle = zoom >= 0.45
        val showSubtitle = zoom >= 0.75

        for (node in model.nodes) {
            val rect = nodeBounds[node.id] ?: continue
            val isRoot = node.id == model.rootNodeId
            val inHighlightPath = highlightedNodeIds.contains(node.id)

            val fill = when {
                isRoot -> JBColor(Color(0xDFF4E0), Color(0x2F4F34))
                inHighlightPath -> JBColor(Color(0xFFF3CF), Color(0x5A4B22))
                else -> JBColor(Color(0xE5EEF8), Color(0x2E3A47))
            }
            val border = when {
                isRoot -> JBColor(Color(0x5BAA5E), Color(0x7BC27D))
                inHighlightPath -> JBColor(Color(0xF5D76E), Color(0xF5D76E))
                else -> JBColor(Color(0x7AA3CC), Color(0x6F8CA8))
            }

            g2.color = fill
            g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 12, 12)
            g2.color = border
            g2.stroke = if (inHighlightPath) BasicStroke(1.9f) else BasicStroke(1.2f)
            g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 12, 12)

            if (showTitle) {
                g2.color = JBColor.foreground()
                g2.font = g2.font.deriveFont(Font.BOLD, 12f)
                val title = trimText("${node.file.name}@${node.tag}", 32)
                g2.drawString(title, rect.x + 10, rect.y + 20)
            }

            if (showSubtitle) {
                g2.font = g2.font.deriveFont(Font.PLAIN, 11f)
                val subtitle = trimText(node.scenarioName ?: "", 36)
                g2.color = JBColor(Color(0x555555), Color(0xB0B0B0))
                g2.drawString(subtitle, rect.x + 10, rect.y + 38)
            }
        }
    }

    private fun recomputeHighlights(nodeId: String?) {
        if (nodeId == null) {
            highlightedEdgeIndexes = emptySet()
            highlightedNodeIds = emptySet()
            return
        }

        val edges = linkedSetOf<Int>()
        val nodes = linkedSetOf<String>()
        val memo = mutableMapOf<String, Boolean>()
        val visiting = mutableSetOf<String>()

        fun canReachRoot(id: String): Boolean {
            memo[id]?.let { return it }
            if (id == model.rootNodeId) {
                memo[id] = true
                return true
            }
            if (!visiting.add(id)) {
                memo[id] = false
                return false
            }

            val reachesRoot = outgoing[id].orEmpty().any { edgeIndex ->
                val next = model.edges[edgeIndex].toNodeId
                canReachRoot(next)
            }

            visiting.remove(id)
            memo[id] = reachesRoot
            return reachesRoot
        }

        fun collectPathToRoot(startId: String) {
            val queue = ArrayDeque<String>()
            val seen = mutableSetOf<String>()
            queue.add(startId)
            seen.add(startId)
            nodes.add(startId)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (edgeIndex in outgoing[current].orEmpty()) {
                    val edge = model.edges[edgeIndex]
                    val next = edge.toNodeId
                    if (!canReachRoot(next)) continue

                    edges.add(edgeIndex)
                    if (nodes.add(next) && seen.add(next)) {
                        queue.add(next)
                    }
                }
            }
        }

        if (canReachRoot(nodeId)) {
            collectPathToRoot(nodeId)
        } else {
            nodes.add(nodeId)
        }

        highlightedEdgeIndexes = edges
        highlightedNodeIds = nodes
    }

    private fun viewport(): JViewport? =
        SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    private fun clampViewPosition(vp: JViewport, desired: Point): Point {
        val maxX = max(0, preferredSize.width - vp.extentSize.width)
        val maxY = max(0, preferredSize.height - vp.extentSize.height)
        return Point(desired.x.coerceIn(0, maxX), desired.y.coerceIn(0, maxY))
    }

    private fun toModelPoint(point: Point): Point {
        if (zoom <= 0.0) return point
        return Point((point.x / zoom).toInt(), (point.y / zoom).toInt())
    }

    private fun trimText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - 3) + "..."
    }

    private fun distance(a: Point2D.Float, b: Point2D.Float): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun moveToward(from: Point2D.Float, to: Point2D.Float, distance: Float): Point2D.Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len == 0f) return Point2D.Float(from.x, from.y)
        val ratio = distance / len
        return Point2D.Float(from.x + dx * ratio, from.y + dy * ratio)
    }

    private fun compactPolylinePoints(points: List<Point2D.Float>): List<Point2D.Float> {
        if (points.size <= 2) return points

        val deduped = mutableListOf<Point2D.Float>()
        for (point in points) {
            val last = deduped.lastOrNull()
            if (last == null || abs(last.x - point.x) > 0.01f || abs(last.y - point.y) > 0.01f) {
                deduped += point
            }
        }

        if (deduped.size <= 2) return deduped

        val compacted = mutableListOf<Point2D.Float>()
        compacted += deduped.first()
        for (i in 1 until deduped.size - 1) {
            val prev = compacted.last()
            val current = deduped[i]
            val next = deduped[i + 1]
            val straightX = abs(prev.x - current.x) <= 0.01f && abs(current.x - next.x) <= 0.01f
            val straightY = abs(prev.y - current.y) <= 0.01f && abs(current.y - next.y) <= 0.01f
            if (!straightX && !straightY) compacted += current
        }
        compacted += deduped.last()
        return compacted
    }

    private fun islandKey(path: String): String {
        val normalized = path.replace('\\', '/')
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return "unknown"

        val withoutFile = if (segments.size > 1) segments.dropLast(1) else emptyList()
        if (withoutFile.isEmpty()) return "root"

        val srcIndex = withoutFile.indexOf("src")
        if (srcIndex != -1) {
            val tail = withoutFile.drop(srcIndex + 1)
            val trimmed = tail.dropWhile { it in setOf("main", "test", "resources", "java", "kotlin") }
            if (trimmed.isNotEmpty()) {
                // Group using the full deep folder path after source roots.
                return trimmed.joinToString("/")
            }
        }

        return withoutFile.joinToString("/")
    }

    private data class IslandBucket(
        val level: Int,
        val path: String
    )

    private data class IslandLayout(
        val key: String,
        val nodes: List<ScenarioImpactGraphNode>,
        val stacks: List<List<ScenarioImpactGraphNode>>,
        val level: Int,
        var rect: Rectangle
    )
}
