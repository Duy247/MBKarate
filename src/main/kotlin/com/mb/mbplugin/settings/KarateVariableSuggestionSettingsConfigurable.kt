package com.mb.mbplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*

/**
 * Settings UI for configuring variable suggestion patterns
 */
class KarateVariableSuggestionSettingsConfigurable(private val project: Project) : Configurable {
    
    private var panel: JPanel? = null
    private var patternListModel: DefaultListModel<String>? = null
    private var patternList: JBList<String>? = null
    
    override fun getDisplayName(): String = "Variable Suggestion Patterns"
    
    override fun createComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = JBUI.Borders.empty(10)
        
        // Create description label
        val descLabel = JLabel("<html>Define regex patterns to extract variable names from Karate feature files.<br>" +
                "Each pattern must have a capturing group (parentheses) for the variable name.<br>" +
                "Example: <code>^\\s*\\*?\\s*def\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=</code></html>")
        descLabel.border = JBUI.Borders.emptyBottom(10)
        mainPanel.add(descLabel, BorderLayout.NORTH)
        
        // Create pattern list
        patternListModel = DefaultListModel()
        patternList = JBList(patternListModel!!)
        
        // Load current patterns
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        settings.patterns.forEach { patternListModel?.addElement(it) }
        
        // Create toolbar with add/remove buttons
        val decorator = ToolbarDecorator.createDecorator(patternList!!)
            .setAddAction { addPattern() }
            .setRemoveAction { removePattern() }
            .setEditAction { editPattern() }
            .createPanel()
        
        mainPanel.add(decorator, BorderLayout.CENTER)
        
        panel = mainPanel
        return mainPanel
    }
    
    private fun addPattern() {
        val pattern = JOptionPane.showInputDialog(
            panel,
            "Enter regex pattern (must include capturing group for variable name):",
            "Add Pattern",
            JOptionPane.PLAIN_MESSAGE
        )
        
        if (!pattern.isNullOrBlank()) {
            // Validate pattern
            try {
                java.util.regex.Pattern.compile(pattern)
                patternListModel?.addElement(pattern)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    panel,
                    "Invalid regex pattern: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
    
    private fun removePattern() {
        val selectedIndex = patternList?.selectedIndex ?: -1
        if (selectedIndex >= 0) {
            patternListModel?.remove(selectedIndex)
        }
    }
    
    private fun editPattern() {
        val selectedIndex = patternList?.selectedIndex ?: -1
        if (selectedIndex >= 0) {
            val currentPattern = patternListModel?.getElementAt(selectedIndex) ?: return
            val newPattern = JOptionPane.showInputDialog(
                panel,
                "Edit regex pattern:",
                currentPattern
            )
            
            if (!newPattern.isNullOrBlank() && newPattern != currentPattern) {
                // Validate pattern
                try {
                    java.util.regex.Pattern.compile(newPattern)
                    patternListModel?.set(selectedIndex, newPattern)
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        panel,
                        "Invalid regex pattern: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }
    
    override fun isModified(): Boolean {
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        val currentPatterns = settings.patterns
        
        if (patternListModel?.size() != currentPatterns.size) return true
        
        for (i in 0 until (patternListModel?.size() ?: 0)) {
            if (patternListModel?.getElementAt(i) != currentPatterns.getOrNull(i)) {
                return true
            }
        }
        
        return false
    }
    
    override fun apply() {
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        settings.patterns.clear()
        
        for (i in 0 until (patternListModel?.size() ?: 0)) {
            patternListModel?.getElementAt(i)?.let { settings.patterns.add(it) }
        }
    }
    
    override fun reset() {
        patternListModel?.clear()
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        settings.patterns.forEach { patternListModel?.addElement(it) }
    }
    
    override fun disposeUIResources() {
        panel = null
        patternListModel = null
        patternList = null
    }
}
