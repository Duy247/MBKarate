package com.mb.mbplugin.karate.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.util.regex.Pattern

class KarateGoToSymbolProvider : GotoDeclarationHandler {

    companion object {
        private val FILE_PATTERN = Pattern.compile("[^ :]+\\.[^ :]+")
    }

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return emptyArray()

        val quotedSplit = sourceElement.text.split("[\"']".toRegex())
        val psiManager = PsiManager.getInstance(sourceElement.project)

        // --- Handle classpath:/src/ paths ---
        val matchedPaths = quotedSplit
            .filter {
                it.startsWith("classpath:", true) ||
                it.startsWith("src/", true) ||
                it.startsWith("src\\", true)
            }
            .flatMap { it.split("@") } // handle @tag navigation
            .filter { it.isNotEmpty() }

        if (matchedPaths.isNotEmpty()) {
            return goToPath(sourceElement, matchedPaths)
        }

        // --- Handle inline file names like data.json ---
        val potentialFilePath = quotedSplit
            .mapNotNull { part ->
                val m = FILE_PATTERN.matcher(part)
                if (m.matches()) m.group() else null
            }
            .firstOrNull()
            ?.let {
                sourceElement.containingFile.virtualFile?.parent?.findFileByRelativePath(it)
            }

        potentialFilePath?.let { vf ->
            psiManager.findFile(vf)?.let { return arrayOf(it) }
        }

        return emptyArray()
    }

    override fun getActionText(context: DataContext): String? = null

    private fun goToPath(sourceElement: PsiElement, filePaths: List<String>): Array<PsiElement> {
        val containingFile = sourceElement.containingFile ?: return emptyArray()
        val module = ModuleUtilCore.findModuleForFile(containingFile) ?: return emptyArray()
        val psiManager = PsiManager.getInstance(sourceElement.project)
        val project = sourceElement.project
        val allRoots = ModuleRootManager.getInstance(module).getSourceRoots(true).toList()

        val firstPart = filePaths.firstOrNull() ?: return emptyArray()
        val tag = filePaths.getOrNull(1)

        val vf: VirtualFile? = when {
            firstPart.startsWith("classpath:", true) -> {
                val relPath = firstPart.removePrefix("classpath:")
                allRoots.asSequence()
                    .mapNotNull { root -> VfsUtilCore.findRelativeFile(relPath, root) }
                    .firstOrNull()
            }
            firstPart.startsWith("src/", true) || firstPart.startsWith("src\\", true) -> {
                val baseDir = project.guessProjectDir()
                baseDir?.let { VfsUtilCore.findRelativeFile(firstPart, it) }
            }
            else -> null
        }

        val psiFile = vf?.let { psiManager.findFile(it) } ?: return emptyArray()

        if (tag == null) return arrayOf(psiFile)

        val fileText = psiFile.text
        val offset = fileText.indexOf("@$tag")
        if (offset >= 0) {
            psiFile.findElementAt(offset)?.let { return arrayOf(it) }
        }

        return arrayOf(psiFile)
    }
}
