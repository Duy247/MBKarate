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
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.elementType
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.mb.mbplugin.karate.index.KarateFeatureIndex
import org.jetbrains.plugins.cucumber.psi.GherkinTag
import org.jetbrains.plugins.cucumber.psi.GherkinTokenTypes
import java.awt.event.MouseEvent
import java.util.regex.Pattern
import javax.swing.JList

/**
 * Shows a gutter impact marker on scenarios that are referenced via:
 * `call read('classpath:...feature@tag')`
 */
class KarateScenarioImpactLineMarkerProvider : LineMarkerProvider {

    companion object {
        private val IMPACT_ICON = AllIcons.Gutter.ImplementedMethod

        private val CALL_READ_PATTERN: Pattern = Pattern.compile(
            "call\\s+read\\s*\\(\\s*['\"](?:classpath:)?([^'\"@]+\\.feature)(?:@([\\p{L}\\p{N}_-]+))?['\"]",
            Pattern.CASE_INSENSITIVE
        )
    }

    @Volatile
    private var cachedModCount: Long = -1L

    @Volatile
    private var cachedUsages: List<CallReadUsage> = emptyList()

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.elementType != GherkinTokenTypes.TAG || element.parent !is GherkinTag) return null
        val tagElement = element.parent as GherkinTag
        val targetFile = tagElement.containingFile?.virtualFile ?: return null
        val tagName = tagElement.name?.removePrefix("@")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val project = element.project
        val usages = findUsagesForTag(project, targetFile, tagName)
        if (usages.isEmpty()) return null

        val tooltip = if (usages.size == 1) {
            "Called by 1 call read"
        } else {
            "Called by ${usages.size} call read statements"
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            IMPACT_ICON,
            { tooltip },
            { mouseEvent, _ -> showUsagePopup(project, usages, mouseEvent) },
            GutterIconRenderer.Alignment.CENTER,
            { "Click to show where this scenario is called" }
        )
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
            val tagMatches = usage.tag != null && usage.tag.equals(scenarioTag, ignoreCase = true)
            val fileMatches = matchesTargetFile(usage.targetFeaturePath, targetFileName, possiblePaths)
            tagMatches && fileMatches
        }.sortedWith(compareBy({ it.callerDisplayPath }, { it.lineNumber }))
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
        val result = mutableListOf<CallReadUsage>()
        val featureFiles = KarateFeatureIndex.getAllFeatureFiles(project)

        for (callerFile in featureFiles) {
            val text = runCatching { VfsUtilCore.loadText(callerFile) }.getOrNull() ?: continue
            val matcher = CALL_READ_PATTERN.matcher(text)
            val callerDisplayPath = toProjectRelativePath(project, callerFile)

            while (matcher.find()) {
                val targetFeaturePath = normalizePath(matcher.group(1))
                val tag = matcher.group(2)?.trim()?.takeIf { it.isNotEmpty() }
                if (tag == null) continue

                val startOffset = matcher.start()
                val lineNumber = countLinesBeforeOffset(text, startOffset)
                val preview = extractLinePreview(text, startOffset)

                result.add(
                    CallReadUsage(
                        callerFile = callerFile,
                        callerDisplayPath = callerDisplayPath,
                        lineNumber = lineNumber,
                        startOffset = startOffset,
                        linePreview = preview,
                        targetFeaturePath = targetFeaturePath,
                        tag = tag
                    )
                )
            }
        }

        return result
    }

    private fun showUsagePopup(project: Project, usages: List<CallReadUsage>, mouseEvent: MouseEvent?) {
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(usages)
            .setTitle("Scenario Usages")
            .setRenderer(object : ColoredListCellRenderer<CallReadUsage>() {
                override fun customizeCellRenderer(
                    list: JList<out CallReadUsage>,
                    value: CallReadUsage?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    if (value == null) return

                    append("${value.callerFile.name}:${value.lineNumber + 1}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            })
            .setItemChosenCallback { usage ->
                navigateToUsage(project, usage)
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

    private fun toProjectRelativePath(project: Project, file: VirtualFile): String {
        val filePath = normalizePath(file.path)
        val projectBase = project.basePath?.let(::normalizePath) ?: return file.name
        return if (filePath.startsWith("$projectBase/")) {
            filePath.removePrefix("$projectBase/")
        } else {
            file.name
        }
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')

    private fun countLinesBeforeOffset(text: String, offset: Int): Int {
        var line = 0
        val safeOffset = offset.coerceIn(0, text.length)
        for (i in 0 until safeOffset) {
            if (text[i] == '\n') line++
        }
        return line
    }

    private fun extractLinePreview(text: String, offset: Int): String {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let { idx ->
            if (idx == -1) 0 else idx + 1
        }
        val lineEnd = text.indexOf('\n', safeOffset).let { idx ->
            if (idx == -1) text.length else idx
        }

        return text.substring(lineStart, lineEnd).trim().take(180)
    }

    private data class CallReadUsage(
        val callerFile: VirtualFile,
        val callerDisplayPath: String,
        val lineNumber: Int,
        val startOffset: Int,
        val linePreview: String,
        val targetFeaturePath: String,
        val tag: String?
    )
}
