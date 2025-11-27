package com.mb.mbplugin.karate.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.mb.mbplugin.settings.KarateVariableSuggestionSettings
import org.jetbrains.plugins.cucumber.psi.GherkinFile

/**
 * Action to toggle variable suggestion feature on/off
 */
class ToggleVariableSuggestionAction : AnAction() {
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        
        // Toggle the setting
        settings.isEnabled = !settings.isEnabled
        
        // Show notification
        val message = if (settings.isEnabled) {
            "Variable suggestion enabled."
        } else {
            "Variable suggestion disabled."
        }
        
        Messages.showInfoMessage(
            project,
            message,
            "Variable Suggestion"
        )
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE)
        
        // Only show this action for Gherkin/Karate feature files
        e.presentation.isEnabledAndVisible = project != null && file is GherkinFile
        
        // Update the text to reflect current state
        if (project != null) {
            val settings = KarateVariableSuggestionSettings.getInstance(project)
            e.presentation.text = if (settings.isEnabled) {
                "Disable Variable Suggestion"
            } else {
                "Enable Variable Suggestion"
            }
        }
    }
}
