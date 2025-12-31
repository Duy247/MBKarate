package com.mb.mbplugin.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
    private var sourceFileListModel: DefaultListModel<String>? = null
    private var sourceFileList: JBList<String>? = null
    
    override fun getDisplayName(): String = "Variable Suggestion Patterns"
    
    override fun createComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = JBUI.Borders.empty(10)
        
        // Create description label
        val descLabel = JLabel("<html>Define regex patterns to extract variable names from Karate feature files.<br>" +
                "Each pattern must have a capturing group (parentheses) for the variable name.<br>" +
                "Optionally include JS/Java source files to suggest variables declared in those files.<br>" +
                "Example: <code>^\\s*\\*?\\s*def\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=</code></html>")
        descLabel.border = JBUI.Borders.emptyBottom(10)
        mainPanel.add(descLabel, BorderLayout.NORTH)
        
        val settings = KarateVariableSuggestionSettings.getInstance(project)

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        val patternPanel = JPanel(BorderLayout())
        patternPanel.border = JBUI.Borders.emptyBottom(10)
        patternPanel.add(JLabel("Patterns"), BorderLayout.NORTH)

        // Create pattern list
        patternListModel = DefaultListModel()
        patternList = JBList(patternListModel!!)

        // Load current patterns
        settings.patterns.forEach { patternListModel?.addElement(it) }

        // Create toolbar with add/remove buttons
        val decorator = ToolbarDecorator.createDecorator(patternList!!)
            .setAddAction { addPattern() }
            .setRemoveAction { removePattern() }
            .setEditAction { editPattern() }
            .createPanel()

        patternPanel.add(decorator, BorderLayout.CENTER)

        val sourceFilePanel = JPanel(BorderLayout())
        sourceFilePanel.add(JLabel("JS/Java source files"), BorderLayout.NORTH)

        sourceFileListModel = DefaultListModel()
        sourceFileList = JBList(sourceFileListModel!!)

        settings.sourceFiles.forEach { sourceFileListModel?.addElement(it) }

        val sourceDecorator = ToolbarDecorator.createDecorator(sourceFileList!!)
            .setAddAction { addSourceFile() }
            .setRemoveAction { removeSourceFile() }
            .setEditAction { editSourceFile() }
            .createPanel()

        sourceFilePanel.add(sourceDecorator, BorderLayout.CENTER)

        contentPanel.add(patternPanel)
        contentPanel.add(sourceFilePanel)

        mainPanel.add(JBScrollPane(contentPanel), BorderLayout.CENTER)
        
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

    private fun addSourceFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        descriptor.title = "Select JS or Java File"
        descriptor.withFileFilter { file ->
            val ext = file.extension?.lowercase()
            ext == "js" || ext == "java"
        }

        val selected = FileChooser.chooseFile(descriptor, project, null)
        val path = selected?.path ?: return

        if (sourceFileListModel?.contains(path) == true) return
        sourceFileListModel?.addElement(path)
    }

    private fun removeSourceFile() {
        val selectedIndex = sourceFileList?.selectedIndex ?: -1
        if (selectedIndex >= 0) {
            sourceFileListModel?.remove(selectedIndex)
        }
    }

    private fun editSourceFile() {
        val selectedIndex = sourceFileList?.selectedIndex ?: -1
        if (selectedIndex < 0) return
        val currentPath = sourceFileListModel?.getElementAt(selectedIndex) ?: return
        val currentFile = LocalFileSystem.getInstance().findFileByPath(currentPath)

        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        descriptor.title = "Select JS or Java File"
        descriptor.withFileFilter { file ->
            val ext = file.extension?.lowercase()
            ext == "js" || ext == "java"
        }

        val selected = FileChooser.chooseFile(descriptor, project, currentFile)
        val newPath = selected?.path ?: return
        if (newPath != currentPath) {
            sourceFileListModel?.set(selectedIndex, newPath)
        }
    }
    
    override fun isModified(): Boolean {
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        val currentPatterns = settings.patterns
        val currentSourceFiles = settings.sourceFiles
        
        if (patternListModel?.size() != currentPatterns.size) return true
        if (sourceFileListModel?.size() != currentSourceFiles.size) return true
        
        for (i in 0 until (patternListModel?.size() ?: 0)) {
            if (patternListModel?.getElementAt(i) != currentPatterns.getOrNull(i)) {
                return true
            }
        }

        for (i in 0 until (sourceFileListModel?.size() ?: 0)) {
            if (sourceFileListModel?.getElementAt(i) != currentSourceFiles.getOrNull(i)) {
                return true
            }
        }
        
        return false
    }
    
    override fun apply() {
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        settings.patterns.clear()
        settings.sourceFiles.clear()
        
        for (i in 0 until (patternListModel?.size() ?: 0)) {
            patternListModel?.getElementAt(i)?.let { settings.patterns.add(it) }
        }

        for (i in 0 until (sourceFileListModel?.size() ?: 0)) {
            sourceFileListModel?.getElementAt(i)?.let { settings.sourceFiles.add(it) }
        }
    }
    
    override fun reset() {
        patternListModel?.clear()
        sourceFileListModel?.clear()
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        settings.patterns.forEach { patternListModel?.addElement(it) }
        settings.sourceFiles.forEach { sourceFileListModel?.addElement(it) }
    }
    
    override fun disposeUIResources() {
        panel = null
        patternListModel = null
        patternList = null
        sourceFileListModel = null
        sourceFileList = null
    }
}
