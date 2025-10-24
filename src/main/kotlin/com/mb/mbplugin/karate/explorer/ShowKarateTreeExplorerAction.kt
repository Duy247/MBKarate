package com.mb.mbplugin.karate.explorer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.cucumber.psi.GherkinFile

/**
 * Action to open the Karate Tree Explorer for the current feature file
 */
class ShowKarateTreeExplorerAction : AnAction("Karate Tree Explorer") {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        if (psiFile !is GherkinFile) {
            return
        }

        // Open the tool window
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Karate Tree Explorer")

        if (toolWindow != null) {
            toolWindow.show {
                // Update the tree with the current file
                val panel = KarateTreeExplorerPanel.getInstance(project)
                panel?.updateTree(psiFile)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        // Only show this action for Gherkin/feature files
        e.presentation.isEnabledAndVisible = project != null && psiFile is GherkinFile
    }
}
