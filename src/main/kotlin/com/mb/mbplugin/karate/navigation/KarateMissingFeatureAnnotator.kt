package com.mb.mbplugin.karate.navigation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.LanguageAnnotators
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.cucumber.psi.GherkinLanguage
import java.util.regex.Pattern

class KarateMissingFeatureAnnotator : Annotator {

    init {
        LanguageAnnotators.INSTANCE.addExplicitExtension(GherkinLanguage.INSTANCE, this)
    }

    private val FILE_PATH_PATTERN = Pattern.compile(
        "['\"](classpath:([^'\"]+\\.(?:feature|json))|(?:src[/\\\\][^'\"]+\\.(?:feature|json)))['\"]",
        Pattern.CASE_INSENSITIVE
    )

    private val processedElements = mutableSetOf<Int>()

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val start = element.textOffset
        if (!processedElements.add(start)) return

        val combinedText = buildString {
            var current: PsiElement? = element
            while (current != null && !current.text.contains("\n")) {
                append(current.text)
                current = current.nextSibling
            }
        }

        val matcher = FILE_PATH_PATTERN.matcher(combinedText)
        if (!matcher.find()) return

        val rawPath = matcher.group(1) ?: return
        val project = element.project
        val containingFile = element.containingFile ?: return
        val module = ModuleUtilCore.findModuleForFile(containingFile) ?: return
        val sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots(true)

        val vFile = when {
            rawPath.startsWith("classpath:", true) -> {
                val featurePath = rawPath.removePrefix("classpath:")
                sourceRoots.asSequence()
                    .mapNotNull { root -> VfsUtilCore.findRelativeFile(featurePath, root) }
                    .firstOrNull()
            }
            rawPath.startsWith("src/", true) || rawPath.startsWith("src\\", true) -> {
                val baseDir = project.baseDir ?: return
                VfsUtilCore.findRelativeFile(rawPath, baseDir)
            }
            else -> null
        }

        if (vFile == null) {
            holder.newAnnotation(
                HighlightSeverity.ERROR,
                "File not found: $rawPath"
            ).range(element.textRange).create()
        }
    }
}
