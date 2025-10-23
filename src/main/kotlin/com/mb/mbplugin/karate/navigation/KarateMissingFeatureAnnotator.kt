package com.mb.mbplugin.karate.navigation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.LanguageAnnotators
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.cucumber.psi.GherkinLanguage
import java.io.File
import java.util.regex.Pattern
import com.intellij.psi.PsiDocumentManager


class KarateMissingFeatureAnnotator : Annotator {

    init {
        LanguageAnnotators.INSTANCE.addExplicitExtension(GherkinLanguage.INSTANCE, this)
    }

    // Support classpath:feature and classpath:feature@tag
    private val CLASSPATH_PATTERN = Pattern.compile(
        "['\"]classpath:([^'\"]+\\.(?:feature|json|txt))(?:@\\w+)?['\"]",
        Pattern.CASE_INSENSITIVE
    )

    // Support literal src/...json or src/...feature
    private val SRC_LITERAL_PATTERN = Pattern.compile(
        "['\"](src(?:/|\\\\)[^'\"]+\\.(?:feature|json|txt))(?:@[\\w-]+)?['\"]",
        Pattern.CASE_INSENSITIVE
    )

    private val processed = mutableSetOf<Int>()

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val start = element.textOffset
        if (!processed.add(start)) return

        val line = buildString {
            var cur: PsiElement? = element
            while (cur != null && !cur.text.contains("\n")) {
                append(cur.text)
                cur = cur.nextSibling
            }
        }

        var anyProblem = false

        // check call read('classpath:...')
        val classpathMatcher = CLASSPATH_PATTERN.matcher(line)
        while (classpathMatcher.find()) {
            val path = classpathMatcher.group(1) ?: continue
            val project = element.project
            val file = element.containingFile ?: continue
            val module = ModuleUtilCore.findModuleForFile(file) ?: continue
            val okInClasspath = findInClasspathRoots(module, path)
            val okInSrcFallback = if (!okInClasspath) findInSrcFolders(project, path) else true
            if (!okInClasspath && !okInSrcFallback) anyProblem = true
        }

        // check src/... direct reference
        val srcMatcher = SRC_LITERAL_PATTERN.matcher(line)
        while (srcMatcher.find()) {
            val raw = srcMatcher.group(1) ?: continue
            if (!existsAtProjectPath(element.project, raw)) anyProblem = true
        }

if (anyProblem) {
    val text = element.text
    val baseOffset = element.textOffset

    // Try matching all paths again within this element text
    val matcher = CLASSPATH_PATTERN.matcher(text)
    while (matcher.find()) {
        val pathStart = baseOffset + matcher.start(1)
        val pathEnd = baseOffset + matcher.end(1)
        val range = com.intellij.openapi.util.TextRange(pathStart, pathEnd)

        holder.newAnnotation(
            HighlightSeverity.WARNING,
            "File not found in project"
        ).range(range).create()
    }

    val srcMatcher = SRC_LITERAL_PATTERN.matcher(text)
    while (srcMatcher.find()) {
        val pathStart = baseOffset + srcMatcher.start(1)
        val pathEnd = baseOffset + srcMatcher.end(1)
        val range = com.intellij.openapi.util.TextRange(pathStart, pathEnd)

        holder.newAnnotation(
            HighlightSeverity.WARNING,
            "File not found in project"
        ).range(range).create()
    }
}

    }

    private fun findInClasspathRoots(module: com.intellij.openapi.module.Module, relPath: String): Boolean {
        val norm = relPath.replace('\\', '/')
        val roots = ModuleRootManager.getInstance(module).getSourceRoots(true)
        return roots.any { root -> VfsUtilCore.findRelativeFile(norm, root) != null }
    }

    private fun existsAtProjectPath(project: Project, relPath: String): Boolean {
        val base = project.basePath ?: return false
        val norm = relPath.replace('\\', '/')
        val f = File(base, norm).path
        val v = LocalFileSystem.getInstance().findFileByPath(f)
        return v != null && v.exists()
    }

    private fun findInSrcFolders(project: Project, relPath: String): Boolean {
        val base = project.basePath ?: return false
        val norm = relPath.replace('\\', '/')
        val candidates = listOf(
            "src/test/resources/$norm",
            "src/test/java/$norm",
            "src/main/resources/$norm",
            "src/main/java/$norm"
        )
        for (p in candidates) {
            val v = LocalFileSystem.getInstance().findFileByPath(File(base, p).path)
            if (v != null && v.exists()) return true
        }
        return false
    }
}
