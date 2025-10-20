package com.mb.mbplugin.karate.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
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
        if (sourceElement == null) {
            return emptyArray()
        }

        val quotedSplit = sourceElement.text.split("[\"']".toRegex())
        val filePaths = quotedSplit
            .filter { it.startsWith("classpath:") }
            .flatMap { it.split("classpath:") }
            .flatMap { it.split("@") }
            .filter { it.isNotEmpty() }

        if (filePaths.isNotEmpty()) {
            return goToClasspath(sourceElement, filePaths)
        } else {
            val potentialFilePath = quotedSplit
                .map { FILE_PATTERN.matcher(it) }
                .filter { it.matches() }
                .map { it.group(0) }
                .firstOrNull()
                ?.let { 
                    sourceElement.containingFile.virtualFile?.parent?.findFileByRelativePath(it)
                }

            if (potentialFilePath != null) {
                val psiFile = PsiManager.getInstance(sourceElement.project).findFile(potentialFilePath)
                return if (psiFile != null) arrayOf(psiFile) else emptyArray()
            }
        }
        return emptyArray()
    }

    override fun getActionText(context: DataContext): String? = null

    private fun goToClasspath(sourceElement: PsiElement, filePaths: List<String>): Array<PsiElement> {
        val containingFile = sourceElement.containingFile ?: return emptyArray()
        val module = ModuleUtilCore.findModuleForFile(containingFile) ?: return emptyArray()
        val psiManager = PsiManager.getInstance(sourceElement.project)

        val allRoots = ModuleRootManager.getInstance(module).getSourceRoots(true).toList()

        val featurePath = filePaths.firstOrNull() ?: return emptyArray()
        val tag = filePaths.getOrNull(1)

        val psiFiles = allRoots.mapNotNull { root ->
            // Try to resolve a relative file inside this root
            val vf = com.intellij.openapi.vfs.VfsUtilCore.findRelativeFile(featurePath, root)
            vf?.let { psiManager.findFile(it) }
        }

        if (psiFiles.isEmpty()) return emptyArray()

        // If no tag is given → open file(s)
        if (tag == null) {
            return psiFiles.toTypedArray()
        }

        // If a tag exists → find the @tag inside the PSI text
        val results = mutableListOf<PsiElement>()
        for (psiFile in psiFiles) {
            val text = psiFile.text
            val offset = text.indexOf("@$tag")
            if (offset >= 0) {
                psiFile.findElementAt(offset)?.let { results.add(it) }
            }
        }

        return results.toTypedArray()
    }

}