package com.mb.mbplugin.karate.playbook

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Tool Window Factory for the Short Playbook
 */
class ShortPlaybookToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val playbookPanel = ShortPlaybookPanel(project)
        val content = ContentFactory.getInstance().createContent(playbookPanel, "", false)
        content.setDisposer(playbookPanel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * Main panel for the Short Playbook
 */
class ShortPlaybookPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<ShortPlaybookEntry>()
    private val list: JBList<ShortPlaybookEntry>
    private val searchField: SearchTextField
    private val scanner = ShortPlaybookScanner(project)
    private var allEntries = listOf<ShortPlaybookEntry>()
    private var refreshScheduled = false

    init {
        // Create search field with enhanced styling
        searchField = SearchTextField(true).apply {
            textEditor.apply {
                emptyText.text = "Search playbook..."
                font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 13)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(java.awt.Color(180, 180, 180)),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)
                )
            }
        }
        
        // Add search listener for immediate feedback
        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = filterEntries()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = filterEntries()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = filterEntries()
        })

        // Create list with enhanced styling
        list = JBList(listModel).apply {
            font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 13)
            fixedCellHeight = 28
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            
            cellRenderer = object : ColoredListCellRenderer<ShortPlaybookEntry>() {
                override fun customizeCellRenderer(
                    list: JList<out ShortPlaybookEntry>,
                    value: ShortPlaybookEntry?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    value?.let {
                        ipad = JBUI.insets(4, 8)
                        
                        append(it.shortDescription, com.intellij.ui.SimpleTextAttributes(
                            com.intellij.ui.SimpleTextAttributes.STYLE_BOLD,
                            if (selected) null else java.awt.Color(180, 180, 180)
                        ))
                        append(" ")
                        append("(@${it.scenarioTag})", com.intellij.ui.SimpleTextAttributes(
                            com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
                            if (selected) null else java.awt.Color(100, 100, 100)
                        ))
                    }
                }
            }
        }

        // Add double-click listener for navigation
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    navigateToSelected()
                }
            }
        })

        // Add to panel with enhanced styling
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 2, 2, 2)
            background = java.awt.Color(250, 250, 250)
            
            // Add search field in center
            add(searchField, BorderLayout.CENTER)
            
            // Add refresh button on the right
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                background = java.awt.Color(250, 250, 250)
                
                val refreshButton = JButton("Refresh").apply {
                    font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 12)
                    toolTipText = "Refresh playbook from all feature files"
                    isFocusPainted = false
                    addActionListener {
                        refreshPlaybook()
                    }
                }
                add(refreshButton)
            }
            add(buttonPanel, BorderLayout.EAST)
        }

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(list).apply {
            border = BorderFactory.createEmptyBorder()
        }, BorderLayout.CENTER)

        // Initial scan
        refreshPlaybook()

        // Listen for file changes
        setupFileListener()
    }

    /**
     * Setup file system listener for auto-refresh
     */
    private fun setupFileListener() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // Check if any .feature files were modified
                val hasFeatureFileChanges = events.any { event ->
                    event.file?.extension == "feature"
                }
                
                if (hasFeatureFileChanges && !refreshScheduled) {
                    refreshScheduled = true
                    // Debounce refresh to avoid excessive updates
                    ApplicationManager.getApplication().invokeLater({
                        refreshScheduled = false
                        refreshPlaybook()
                    }, com.intellij.openapi.application.ModalityState.nonModal())
                }
            }
        })
    }

    /**
     * Refresh the playbook by scanning all feature files
     */
    private fun refreshPlaybook() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = com.intellij.openapi.application.ReadAction.compute<List<ShortPlaybookEntry>, RuntimeException> {
                scanner.scanForShortDescriptions()
            }
            
            ApplicationManager.getApplication().invokeLater({
                allEntries = entries.sortedBy { it.shortDescription.lowercase() }
                filterEntries()
            }, com.intellij.openapi.application.ModalityState.nonModal())
        }
    }

    /**
     * Filter entries based on search text
     */
    private fun filterEntries() {
        val searchText = searchField.text.trim().lowercase()
        
        listModel.clear()
        
        val filtered = if (searchText.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry ->
                entry.shortDescription.lowercase().contains(searchText) ||
                entry.scenarioTag.lowercase().contains(searchText)
            }
        }
        
        filtered.forEach { listModel.addElement(it) }
    }

    /**
     * Navigate to the selected scenario
     */
    private fun navigateToSelected() {
        val selected = list.selectedValue ?: return
        
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(selected.filePath) ?: return
        
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? GherkinFile ?: return
        
        // Find the scenario with the matching tag
        val parser = com.mb.mbplugin.karate.explorer.KarateTreeParser(project)
        val scenarios = parser.parseFeatureFile(psiFile)
        
        val scenario = scenarios.firstOrNull { it.tag == selected.scenarioTag }
        if (scenario != null) {
            val offset = scenario.psiElement.textOffset
            val descriptor = OpenFileDescriptor(project, virtualFile, offset)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        } else {
            // If scenario not found, just open the file
            val descriptor = OpenFileDescriptor(project, virtualFile)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    override fun dispose() {
        // Cleanup is handled automatically by Disposable API
    }

    companion object {
        /**
         * Get the playbook panel from a project's tool window
         */
        fun getInstance(project: Project): ShortPlaybookPanel? {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Short Playbook") ?: return null
            
            val content = toolWindow.contentManager.getContent(0) ?: return null
            return content.component as? ShortPlaybookPanel
        }
    }
}

/**
 * Data class representing a playbook entry
 */
data class ShortPlaybookEntry(
    val shortDescription: String,
    val scenarioTag: String,
    val filePath: String,
    val scenarioName: String
)
