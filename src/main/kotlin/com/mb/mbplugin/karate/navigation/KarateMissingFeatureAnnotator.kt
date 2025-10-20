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

    private val CALL_READ_PATTERN = Pattern.compile(
        "call\\s+read\\s*\\(\\s*['\"]classpath:([^'\"]+\\.feature)(@\\w+)?['\"]",
        Pattern.CASE_INSENSITIVE
    )

    private val processedElements = mutableSetOf<Int>()

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val start = element.textOffset
        if (processedElements.contains(start)) return
        processedElements.add(start)

        val text = element.text
        if (!text.contains("call read(")) return

        val combinedText = buildString {
            var current: PsiElement? = element
            while (current != null && !current.text.contains("\n")) {
                append(current.text)
                current = current.nextSibling
            }
        }

        val matcher = CALL_READ_PATTERN.matcher(combinedText)
        if (!matcher.find()) return

        val featurePath = matcher.group(1) ?: return
        val project = element.project

        // --- check file existence ---
        val containingFile = element.containingFile ?: return
        val module = ModuleUtilCore.findModuleForFile(containingFile) ?: return
        val sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots(true)

        val found = sourceRoots.any { root ->
            VfsUtilCore.findRelativeFile(featurePath, root) != null
        }
        println("Checking feature path: $featurePath")
        sourceRoots.forEach { println(" - Source root: ${it.path}") }

        if (!found) {
            holder.newAnnotation(
                HighlightSeverity.ERROR,
                "Feature file not found: classpath:$featurePath"
            ).range(element.textRange).create()
        }
    }
}
