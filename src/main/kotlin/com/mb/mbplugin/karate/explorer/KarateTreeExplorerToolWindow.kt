package com.mb.mbplugin.karate.explorer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Tool Window Factory for the Karate Tree Explorer
 */
class KarateTreeExplorerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val explorerPanel = KarateTreeExplorerPanel(project)
        val content = ContentFactory.getInstance().createContent(explorerPanel, "", false)
        content.setDisposer(explorerPanel) // Register the panel as disposable
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * Main panel for the Karate Tree Explorer
 */
class KarateTreeExplorerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val tree: Tree
    private val treeBuilder = KarateTreeBuilder(project)
    private var currentFile: PsiFile? = null

    init {
        // Create the tree
        tree = Tree(DefaultTreeModel(DefaultMutableTreeNode("No file selected")))
        tree.isRootVisible = true
        tree.showsRootHandles = true

        // Custom cell renderer for icons
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree?,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                
                if (value is DefaultMutableTreeNode) {
                    when (val userObject = value.userObject) {
                        is KarateTreeNode -> {
                            icon = userObject.icon
                            text = userObject.displayName
                        }
                    }
                }
                
                return component
            }
        }

        // Add mouse listener for navigation
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    handleDoubleClick()
                }
            }
        })

        // Add to panel
        val scrollPane = JBScrollPane(tree)
        add(scrollPane, BorderLayout.CENTER)

        // Add info label at the top
        val infoLabel = JLabel("Right-click in a feature file and select 'Karate Tree Explorer' to view its structure")
        infoLabel.border = javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 5)
        add(infoLabel, BorderLayout.NORTH)
    }

    /**
     * Update the tree with a new feature file
     */
    fun updateTree(psiFile: PsiFile) {
        if (psiFile !is GherkinFile) {
            showError("Not a feature file")
            return
        }

        currentFile = psiFile
        
        // Add document listener for auto-refresh with the new API
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        if (document != null) {
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    // Debounce updates to avoid excessive refreshes
                    ApplicationManager.getApplication().invokeLater {
                        refreshTree()
                    }
                }
            }, this) // Pass the disposable (this panel)
        }
        
        refreshTree()
    }

    /**
     * Refresh the tree with the current file
     */
    private fun refreshTree() {
        val file = currentFile ?: return
        
        try {
            // Commit any pending changes to PSI
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            
            val model = treeBuilder.buildTreeModel(file)
            tree.model = model
            
            // Expand the root node by default
            tree.expandRow(0)
        } catch (e: Exception) {
            showError("Error parsing file: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Handle double-click on tree nodes
     */
    private fun handleDoubleClick() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val karateNode = node.userObject as? KarateTreeNode ?: return

        when (karateNode) {
            is CallReadNode -> {
                // Navigate to the call read target
                navigateToCallRead(karateNode)
            }
            is ScenarioNode, is StepNode, is FeatureNode -> {
                // Navigate to the PSI element
                karateNode.psiElement?.let { navigateToPsiElement(it) }
            }
            else -> {
                // Do nothing for other node types
            }
        }
    }

    /**
     * Navigate to a call read's target
     */
    private fun navigateToCallRead(callReadNode: CallReadNode) {
        val parser = KarateTreeParser(project)
        val callReadInfo = KarateTreeParser.CallReadInfo(
            featurePath = callReadNode.featurePath,
            scenarioTag = callReadNode.scenarioTag,
            psiElement = callReadNode.psiElement ?: return,
            fullCallText = callReadNode.fullCallText
        )

        val resolved = parser.resolveCallRead(callReadInfo)
        if (resolved != null) {
            if (resolved.scenarios.isNotEmpty()) {
                // Navigate to the first scenario
                navigateToPsiElement(resolved.scenarios.first().psiElement)
            } else {
                // Navigate to the file
                navigateToPsiElement(resolved.psiFile)
            }
        }
    }

    /**
     * Navigate to a PSI element in the editor
     */
    private fun navigateToPsiElement(psiElement: com.intellij.psi.PsiElement) {
        val file = psiElement.containingFile?.virtualFile ?: return
        val offset = psiElement.textOffset
        
        val descriptor = OpenFileDescriptor(project, file, offset)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    /**
     * Show an error message in the tree
     */
    private fun showError(message: String) {
        val rootNode = DefaultMutableTreeNode(message)
        tree.model = DefaultTreeModel(rootNode)
    }

    /**
     * Dispose method for cleanup when the tool window is closed
     */
    override fun dispose() {
        // Cleanup is handled automatically by the Disposable API
        // Document listeners registered with a disposable are automatically removed
    }

    companion object {
        /**
         * Get the tree explorer panel from a project's tool window
         */
        fun getInstance(project: Project): KarateTreeExplorerPanel? {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Karate Tree Explorer") ?: return null
            
            val content = toolWindow.contentManager.getContent(0) ?: return null
            return content.component as? KarateTreeExplorerPanel
        }
    }
}
