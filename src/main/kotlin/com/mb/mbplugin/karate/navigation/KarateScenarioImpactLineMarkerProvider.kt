package com.mb.mbplugin.karate.navigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.mb.mbplugin.karate.explorer.KarateTreeParser
import com.mb.mbplugin.karate.index.KarateFeatureIndex
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinScenario
import org.jetbrains.plugins.cucumber.psi.GherkinScenarioOutline
import org.jetbrains.plugins.cucumber.psi.GherkinTag
import org.jetbrains.plugins.cucumber.psi.GherkinTokenTypes
import java.awt.event.MouseEvent
import javax.swing.JList

/**
 * Shows a gutter impact marker on scenario tag lines referenced via:
 * `call read('classpath:...feature@tag')`.
 */
class KarateScenarioImpactLineMarkerProvider : LineMarkerProvider {

    companion object {
        private val IMPACT_ICON = AllIcons.Gutter.ImplementedMethod
        private const val MAX_GRAPH_DEPTH = 10
        private const val BACKGROUND_NODE_TAG = "<BACKGROUND>"
    }

    @Volatile
    private var cachedModCount: Long = -1L

    @Volatile
    private var cachedUsages: List<CallReadUsage> = emptyList()

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.elementType != GherkinTokenTypes.TAG || element.parent !is GherkinTag) return null
        val tagElement = element.parent as GherkinTag
        val targetFile = tagElement.containingFile?.virtualFile ?: return null
        val targetTag = tagElement.name?.removePrefix("@")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val project = element.project
        val usages = findUsagesForTag(project, targetFile, targetTag)
        if (usages.isEmpty()) return null

        val tooltip = if (usages.size == 1) {
            "Called by 1 call"
        } else {
            "Called by ${usages.size} call statements"
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            IMPACT_ICON,
            { tooltip },
            { mouseEvent, _ -> showUsagePopup(project, targetFile, targetTag, usages, mouseEvent) },
            GutterIconRenderer.Alignment.CENTER,
            { "Click to show where this scenario is called" }
        )
    }

    private fun showUsagePopup(
        project: Project,
        targetFile: VirtualFile,
        targetTag: String,
        usages: List<CallReadUsage>,
        mouseEvent: MouseEvent?
    ) {
        val items = listOf(PopupItem.Investigate) + usages.map { PopupItem.Usage(it) }

        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Scenario Usages")
            .setRenderer(object : ColoredListCellRenderer<PopupItem>() {
                override fun customizeCellRenderer(
                    list: JList<out PopupItem>,
                    value: PopupItem?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    when (value) {
                        is PopupItem.Usage -> {
                            append("${value.usage.callerFile.name}:${value.usage.lineNumber + 1}")
                        }
                        PopupItem.Investigate -> {
                            append("Investigate...", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        }
                        null -> Unit
                    }
                }
            })
            .setItemChosenCallback { selected ->
                when (selected) {
                    is PopupItem.Usage -> navigateToUsage(project, selected.usage)
                    PopupItem.Investigate -> openImpactGraph(project, targetFile, targetTag)
                    null -> Unit
                }
            }
            .createPopup()

        if (mouseEvent != null) {
            val component = mouseEvent.component
            val point = java.awt.Point(mouseEvent.point)
            javax.swing.SwingUtilities.convertPointToScreen(point, component)
            point.y += 10
            popup.showInScreenCoordinates(component, point)
        } else {
            popup.showCenteredInCurrentWindow(project)
        }
    }

    private fun openImpactGraph(project: Project, rootFile: VirtualFile, rootTag: String) {
        val model = buildImpactGraphModel(project, rootFile, rootTag)
        KarateScenarioImpactGraphDialog(project, model).show()
    }

    private fun buildImpactGraphModel(
        project: Project,
        rootFile: VirtualFile,
        rootTag: String
    ): ScenarioImpactGraphModel {
        val nodesById = linkedMapOf<String, ScenarioImpactGraphNode>()
        val edgesById = linkedMapOf<String, ScenarioImpactGraphEdge>()
        val queue = ArrayDeque<TraversalState>()
        val visited = mutableSetOf<String>()

        val (rootScenarioName, rootOffset) = findTagInfo(project, rootFile, rootTag)
        val rootId = nodeId(rootFile, rootTag)
        nodesById[rootId] = ScenarioImpactGraphNode(
            id = rootId,
            file = rootFile,
            tag = rootTag,
            scenarioName = rootScenarioName,
            level = 0,
            navigateOffset = rootOffset
        )
        queue.add(TraversalState(rootFile, rootTag, rootId, 0))
        visited.add(rootId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.level >= MAX_GRAPH_DEPTH) continue

            val callers = if (current.tag == BACKGROUND_NODE_TAG) {
                findUsagesForFeature(project, current.file)
            } else {
                findUsagesForTag(project, current.file, current.tag)
            }
            for (usage in callers) {
                val callerTag = when {
                    !usage.callerScenarioTag.isNullOrBlank() -> usage.callerScenarioTag
                    usage.callerIsBackground -> BACKGROUND_NODE_TAG
                    else -> continue
                }
                val callerId = nodeId(usage.callerFile, callerTag)
                val callerLevel = current.level + 1

                if (!nodesById.containsKey(callerId)) {
                    nodesById[callerId] = ScenarioImpactGraphNode(
                        id = callerId,
                        file = usage.callerFile,
                        tag = callerTag,
                        scenarioName = when {
                            usage.callerIsBackground -> "Background"
                            else -> usage.callerScenarioName
                        },
                        level = callerLevel,
                        navigateOffset = usage.startOffset
                    )
                }

                val edgeId = "$callerId->${current.nodeId}"
                val existing = edgesById[edgeId]
                if (existing == null) {
                    edgesById[edgeId] = ScenarioImpactGraphEdge(
                        fromNodeId = callerId,
                        toNodeId = current.nodeId,
                        label = "${usage.callerFile.name}:${usage.lineNumber + 1}",
                        usageCount = 1
                    )
                } else {
                    edgesById[edgeId] = existing.copy(usageCount = existing.usageCount + 1)
                }

                if (visited.add(callerId)) {
                    queue.add(TraversalState(usage.callerFile, callerTag, callerId, callerLevel))
                }
            }
        }

        return ScenarioImpactGraphModel(
            rootNodeId = rootId,
            nodes = nodesById.values.toList(),
            edges = edgesById.values.toList()
        )
    }

    private fun findTagInfo(project: Project, file: VirtualFile, tag: String): Pair<String?, Int> {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? GherkinFile ?: return null to 0
        val tags = PsiTreeUtil.findChildrenOfType(psiFile, GherkinTag::class.java)
        val matched = tags.firstOrNull { it.name?.removePrefix("@")?.equals(tag, true) == true } ?: return null to 0

        val nextScenario = findNextScenario(matched)
        val scenarioName = when (nextScenario) {
            is GherkinScenario -> nextScenario.scenarioName
            is GherkinScenarioOutline -> nextScenario.scenarioName
            else -> null
        }

        return scenarioName to matched.textOffset
    }

    private fun findNextScenario(tagElement: GherkinTag): PsiElement? {
        var current: PsiElement? = tagElement.nextSibling
        while (current != null) {
            if (current is GherkinScenario || current is GherkinScenarioOutline) return current
            current = current.nextSibling
        }
        return null
    }

    private fun findUsagesForTag(
        project: Project,
        targetFile: VirtualFile,
        scenarioTag: String
    ): List<CallReadUsage> {
        val usages = getOrBuildUsageIndex(project)
        val possiblePaths = collectPossibleClasspathPaths(project, targetFile)
        val targetFileName = targetFile.name

        return usages.filter { usage ->
            val tagMatches = usage.targetTag.equals(scenarioTag, ignoreCase = true)
            val fileMatches = matchesTargetFile(usage.targetFeaturePath, targetFileName, possiblePaths)
            tagMatches && fileMatches
        }.sortedWith(compareBy({ it.callerFile.name }, { it.lineNumber }))
    }

    private fun findUsagesForFeature(
        project: Project,
        targetFile: VirtualFile
    ): List<CallReadUsage> {
        val usages = getOrBuildUsageIndex(project)
        val possiblePaths = collectPossibleClasspathPaths(project, targetFile)
        val targetFileName = targetFile.name

        return usages.filter { usage ->
            matchesTargetFile(usage.targetFeaturePath, targetFileName, possiblePaths)
        }.sortedWith(compareBy({ it.callerFile.name }, { it.lineNumber }))
    }

    private fun getOrBuildUsageIndex(project: Project): List<CallReadUsage> {
        val modCount = PsiManager.getInstance(project).modificationTracker.modificationCount
        if (modCount == cachedModCount) return cachedUsages

        synchronized(this) {
            if (modCount == cachedModCount) return cachedUsages
            cachedUsages = buildUsageIndex(project)
            cachedModCount = modCount
        }
        return cachedUsages
    }

    private fun buildUsageIndex(project: Project): List<CallReadUsage> {
        val parser = KarateTreeParser(project)
        val result = mutableListOf<CallReadUsage>()

        for (callerFile in KarateFeatureIndex.getAllFeatureFiles(project)) {
            val psiFile = PsiManager.getInstance(project).findFile(callerFile) as? GherkinFile ?: continue
            val scenarios = parser.parseFeatureFile(psiFile)
            val document = FileDocumentManager.getInstance().getDocument(callerFile)

            for (scenario in scenarios) {
                for (step in scenario.steps) {
                    for (callRead in step.callReads) {
                        val targetTag = callRead.scenarioTag?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        val offset = callRead.psiElement.textOffset
                        val lineNumber = document?.getLineNumber(offset) ?: 0

                        result.add(
                            CallReadUsage(
                                callerFile = callerFile,
                                lineNumber = lineNumber,
                                startOffset = offset,
                                targetFeaturePath = normalizePath(callRead.featurePath),
                                targetTag = targetTag,
                                callerScenarioTag = scenario.tag,
                                callerScenarioName = scenario.name,
                                callerIsBackground = scenario.isBackground
                            )
                        )
                    }
                }
            }
        }

        return result
    }

    private fun navigateToUsage(project: Project, usage: CallReadUsage) {
        val document = FileDocumentManager.getInstance().getDocument(usage.callerFile)
        val offset = if (document != null && usage.lineNumber in 0 until document.lineCount) {
            document.getLineStartOffset(usage.lineNumber)
        } else {
            usage.startOffset
        }
        OpenFileDescriptor(project, usage.callerFile, offset).navigate(true)
    }

    private fun matchesTargetFile(
        targetFeaturePath: String,
        targetFileName: String,
        possiblePaths: Set<String>
    ): Boolean {
        val normalized = normalizePath(targetFeaturePath).trimStart('/')
        if (normalized.isEmpty()) return false

        if (possiblePaths.any { it.equals(normalized, ignoreCase = true) }) return true
        if (normalized.equals(targetFileName, ignoreCase = true)) return true

        return possiblePaths.any { path ->
            normalized.endsWith("/$path", ignoreCase = true) || path.endsWith("/$normalized", ignoreCase = true)
        }
    }

    private fun collectPossibleClasspathPaths(project: Project, targetFile: VirtualFile): Set<String> {
        val filePath = normalizePath(targetFile.path)
        val result = linkedSetOf<String>()

        result.add(targetFile.name)

        val projectBase = project.basePath?.let(::normalizePath)
        if (projectBase != null && filePath.startsWith("$projectBase/")) {
            result.add(filePath.removePrefix("$projectBase/"))
        }

        ModuleManager.getInstance(project).modules.forEach { module ->
            ModuleRootManager.getInstance(module).sourceRoots.forEach { root ->
                val rootPath = normalizePath(root.path)
                if (filePath.startsWith("$rootPath/")) {
                    result.add(filePath.removePrefix("$rootPath/"))
                }
            }
        }

        return result.mapTo(linkedSetOf()) { normalizePath(it).trimStart('/') }
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')

    private fun nodeId(file: VirtualFile, tag: String): String =
        "${normalizePath(file.path)}@$tag".lowercase()

    private sealed interface PopupItem {
        data class Usage(val usage: CallReadUsage) : PopupItem
        data object Investigate : PopupItem
    }

    private data class TraversalState(
        val file: VirtualFile,
        val tag: String,
        val nodeId: String,
        val level: Int
    )

    private data class CallReadUsage(
        val callerFile: VirtualFile,
        val lineNumber: Int,
        val startOffset: Int,
        val targetFeaturePath: String,
        val targetTag: String,
        val callerScenarioTag: String?,
        val callerScenarioName: String?,
        val callerIsBackground: Boolean
    )
}
