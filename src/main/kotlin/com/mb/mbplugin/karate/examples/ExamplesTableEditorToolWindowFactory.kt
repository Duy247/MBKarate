package com.mb.mbplugin.karate.examples

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the Examples Table Editor tool window
 */
class ExamplesTableEditorToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val editorPanel = ExamplesTableEditorPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(editorPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
