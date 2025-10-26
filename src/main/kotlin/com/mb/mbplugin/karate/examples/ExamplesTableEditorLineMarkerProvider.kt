package com.mb.mbplugin.karate.examples

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import org.jetbrains.plugins.cucumber.psi.GherkinTokenTypes
import javax.swing.Icon

/**
 * Provides a gutter icon for Examples: blocks in Scenario Outlines
 * Clicking the icon opens a spreadsheet-like editor in a tool window
 */
class ExamplesTableEditorLineMarkerProvider : LineMarkerProvider {
    
    companion object {
        private val SPREADSHEET_ICON: Icon = IconLoader.getIcon("/icons/spreadsheet.svg", ExamplesTableEditorLineMarkerProvider::class.java)
    }
    
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Check if this is the "Examples:" keyword
        if (element.elementType != GherkinTokenTypes.EXAMPLES_KEYWORD) {
            return null
        }
        
        val tooltipText = "Edit Examples table in spreadsheet view"
        
        return LineMarkerInfo(
            element,
            element.textRange,
            SPREADSHEET_ICON,
            { tooltipText },
            { _, psiElement ->
                openTableEditor(psiElement)
            },
            GutterIconRenderer.Alignment.CENTER,
            { "Click to edit Examples table as spreadsheet" }
        )
    }
    
    /**
     * Open the table editor tool window and load the table data
     */
    private fun openTableEditor(element: PsiElement) {
        val project = element.project
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Examples Table Editor")
        
        if (toolWindow != null) {
            // Parse the table data from the Examples block
            val tableData = ExamplesTableParser.parseExamplesBlock(element)
            
            // Get the tool window content and update it
            val contentManager = toolWindow.contentManager
            val content = contentManager.contents.firstOrNull()
            
            if (content != null) {
                val component = content.component
                if (component is ExamplesTableEditorPanel) {
                    component.loadTableData(tableData, element)
                }
            }
            
            // Show the tool window
            toolWindow.show()
        }
    }
}
