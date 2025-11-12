package com.mb.mbplugin.karate.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for building documentation with @param, @short, @return, @snippet_before, @snippet_after
 */
class DocumentationBuilderDialog(
    project: Project,
    private val scenarioTag: String,
    existingDoc: DocumentHelperAction.ExistingDocumentation? = null
) : DialogWrapper(project) {

    private val shortDescField = JBTextField()
    private val bodyTextArea = JTextArea(5, 50)
    private val snippetBeforeTextArea = JTextArea(5, 50)
    private val snippetAfterTextArea = JTextArea(5, 50)
    
    private val paramListModel = DefaultListModel<ParamEntry>()
    private val returnListModel = DefaultListModel<ReturnEntry>()

    init {
        title = if (existingDoc != null) "Edit Documentation for @$scenarioTag" else "Create Documentation for @$scenarioTag"
        setSize(950, 750)
        
        // Configure text fields with better fonts
        configureTextComponents()
        
        // Pre-populate fields if existing documentation is provided
        if (existingDoc != null) {
            parseAndPopulateExistingDoc(existingDoc.content)
        }
        
        init()
    }
    
    private fun configureTextComponents() {
        val monoFont = Font("JetBrains Mono", Font.PLAIN, 13)
        val uiFont = UIUtil.getLabelFont().deriveFont(13f)
        
        // Configure short description field
        shortDescField.font = uiFont
        
        // Configure text areas with monospace font
        bodyTextArea.font = monoFont
        bodyTextArea.lineWrap = true
        bodyTextArea.wrapStyleWord = true
        bodyTextArea.tabSize = 2
        bodyTextArea.margin = JBUI.insets(5)
        
        snippetBeforeTextArea.font = monoFont
        snippetBeforeTextArea.lineWrap = false
        snippetBeforeTextArea.tabSize = 2
        snippetBeforeTextArea.margin = JBUI.insets(5)
        
        snippetAfterTextArea.font = monoFont
        snippetAfterTextArea.lineWrap = false
        snippetAfterTextArea.tabSize = 2
        snippetAfterTextArea.margin = JBUI.insets(5)
    }
    
    private fun parseAndPopulateExistingDoc(docContent: String) {
        // Use the KarateFeatureDocumentationProvider to parse the documentation
        val provider = com.mb.mbplugin.karate.documentation.KarateFeatureDocumentationProvider()
        val parsed = provider.parseDocDirectives(docContent)
        
        // Populate short description
        parsed.shortDescription?.let { shortDescField.text = it }
        
        // Populate body
        bodyTextArea.text = parsed.body
        
        // Populate parameters
        parsed.params.forEach { param ->
            paramListModel.addElement(ParamEntry(param.name, param.tags, param.description))
        }
        
        // Populate returns
        parsed.returns.forEach { ret ->
            returnListModel.addElement(ReturnEntry(ret.name, ret.description))
        }
        
        // Populate snippets
        parsed.snippetBefore?.let { snippetBeforeTextArea.text = it }
        parsed.snippetAfter?.let { snippetAfterTextArea.text = it }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = JBUI.Borders.empty(10)
        
        // Create tabbed pane for better organization
        val tabbedPane = JTabbedPane()
        tabbedPane.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
        
        // Tab 1: Basic Info (Short description and body)
        tabbedPane.addTab("Basic Info", createBasicInfoPanel())
        
        // Tab 2: Parameters
        tabbedPane.addTab("Parameters", createParametersPanel())
        
        // Tab 3: Returns
        tabbedPane.addTab("Returns", createReturnsPanel())
        
        // Tab 4: Code Snippets
        tabbedPane.addTab("Snippets", createSnippetsPanel())
        
        mainPanel.add(tabbedPane, BorderLayout.CENTER)
        
        return mainPanel
    }

    private fun createBasicInfoPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(15)
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(8)
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.NORTHWEST
        
        val labelFont = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
        
        // Short description
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        val shortLabel = JLabel("@short:")
        shortLabel.font = labelFont
        panel.add(shortLabel, gbc)
        
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(shortDescField, gbc)
        
        // Body
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.NORTHEAST
        val descLabel = JLabel("Description:")
        descLabel.font = labelFont
        panel.add(descLabel, gbc)
        
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        val bodyScrollPane = JBScrollPane(bodyTextArea)
        bodyScrollPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIUtil.getBoundsColor(), 1),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        )
        panel.add(bodyScrollPane, gbc)
        
        return panel
    }

    private fun createParametersPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = JBUI.Borders.empty(15)
        
        val list = JBList(paramListModel)
        list.cellRenderer = ParamListCellRenderer()
        list.font = UIUtil.getLabelFont().deriveFont(13f)
        list.fixedCellHeight = 30
        
        val scrollPane = JBScrollPane(list)
        scrollPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIUtil.getBoundsColor(), 1),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        )
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Buttons panel
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 5))
        val buttonFont = UIUtil.getLabelFont().deriveFont(12f)
        
        val addButton = JButton("Add Parameter")
        addButton.font = buttonFont
        addButton.addActionListener {
            val dialog = ParamEntryDialog(null, null)
            if (dialog.showAndGet()) {
                val param = dialog.getParamEntry()
                if (param != null) {
                    paramListModel.addElement(param)
                }
            }
        }
        
        val editButton = JButton("Edit")
        editButton.font = buttonFont
        editButton.addActionListener {
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                val param = paramListModel.getElementAt(selectedIndex)
                val dialog = ParamEntryDialog(null, param)
                if (dialog.showAndGet()) {
                    val updatedParam = dialog.getParamEntry()
                    if (updatedParam != null) {
                        paramListModel.setElementAt(updatedParam, selectedIndex)
                    }
                }
            }
        }
        
        val removeButton = JButton("Remove")
        removeButton.font = buttonFont
        removeButton.addActionListener {
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                paramListModel.remove(selectedIndex)
            }
        }
        
        val moveUpButton = JButton("Move Up")
        moveUpButton.font = buttonFont
        moveUpButton.toolTipText = "Move Up"
        moveUpButton.addActionListener {
            val selectedIndex = list.selectedIndex
            if (selectedIndex > 0) {
                val param = paramListModel.remove(selectedIndex)
                paramListModel.add(selectedIndex - 1, param)
                list.selectedIndex = selectedIndex - 1
            }
        }
        
        val moveDownButton = JButton("Move Down")
        moveDownButton.font = buttonFont
        moveDownButton.toolTipText = "Move Down"
        moveDownButton.addActionListener {
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0 && selectedIndex < paramListModel.size() - 1) {
                val param = paramListModel.remove(selectedIndex)
                paramListModel.add(selectedIndex + 1, param)
                list.selectedIndex = selectedIndex + 1
            }
        }
        
        buttonsPanel.add(addButton)
        buttonsPanel.add(editButton)
        buttonsPanel.add(removeButton)
        buttonsPanel.add(moveUpButton)
        buttonsPanel.add(moveDownButton)
        
        panel.add(buttonsPanel, BorderLayout.SOUTH)
        
        return panel
    }

    private fun createReturnsPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = JBUI.Borders.empty(15)
        
        val list = JBList(returnListModel)
        list.cellRenderer = ReturnListCellRenderer()
        list.font = UIUtil.getLabelFont().deriveFont(13f)
        list.fixedCellHeight = 30
        
        val scrollPane = JBScrollPane(list)
        scrollPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIUtil.getBoundsColor(), 1),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        )
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Buttons panel
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 5))
        val buttonFont = UIUtil.getLabelFont().deriveFont(12f)
        
        val addButton = JButton("Add Return")
        addButton.font = buttonFont
        addButton.addActionListener {
            val dialog = ReturnEntryDialog(null, null)
            if (dialog.showAndGet()) {
                val returnEntry = dialog.getReturnEntry()
                if (returnEntry != null) {
                    returnListModel.addElement(returnEntry)
                }
            }
        }
        
        val editButton = JButton("Edit")
        editButton.font = buttonFont
        editButton.addActionListener {
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                val returnEntry = returnListModel.getElementAt(selectedIndex)
                val dialog = ReturnEntryDialog(null, returnEntry)
                if (dialog.showAndGet()) {
                    val updatedReturn = dialog.getReturnEntry()
                    if (updatedReturn != null) {
                        returnListModel.setElementAt(updatedReturn, selectedIndex)
                    }
                }
            }
        }
        
        val removeButton = JButton("Remove")
        removeButton.font = buttonFont
        removeButton.addActionListener {
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                returnListModel.remove(selectedIndex)
            }
        }
        
        buttonsPanel.add(addButton)
        buttonsPanel.add(editButton)
        buttonsPanel.add(removeButton)
        
        panel.add(buttonsPanel, BorderLayout.SOUTH)
        
        return panel
    }

    private fun createSnippetsPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(15)
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(8)
        gbc.fill = GridBagConstraints.BOTH
        gbc.anchor = GridBagConstraints.NORTHWEST
        
        val labelFont = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
        
        // Snippet Before
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        val snippetBeforeLabel = JLabel("@snippet_before:")
        snippetBeforeLabel.font = labelFont
        panel.add(snippetBeforeLabel, gbc)
        
        gbc.gridy = 1
        gbc.weightx = 1.0
        gbc.weighty = 0.5
        gbc.fill = GridBagConstraints.BOTH
        val snippetBeforeScrollPane = JBScrollPane(snippetBeforeTextArea)
        snippetBeforeScrollPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIUtil.getBoundsColor(), 1),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        )
        panel.add(snippetBeforeScrollPane, gbc)
        
        // Snippet After
        gbc.gridy = 2
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        val snippetAfterLabel = JLabel("@snippet_after:")
        snippetAfterLabel.font = labelFont
        panel.add(snippetAfterLabel, gbc)
        
        gbc.gridy = 3
        gbc.weighty = 0.5
        gbc.fill = GridBagConstraints.BOTH
        val snippetAfterScrollPane = JBScrollPane(snippetAfterTextArea)
        snippetAfterScrollPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIUtil.getBoundsColor(), 1),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        )
        panel.add(snippetAfterScrollPane, gbc)
        
        return panel
    }

    fun buildDocumentation(): String {
        val sb = StringBuilder()
        
        // Add short description if provided
        val shortDesc = shortDescField.text.trim()
        if (shortDesc.isNotEmpty()) {
            sb.append("@short $shortDesc\n\n")
        }
        
        // Add body
        val body = bodyTextArea.text.trim()
        if (body.isNotEmpty()) {
            sb.append(body).append("\n\n")
        }
        
        // Add parameters
        for (i in 0 until paramListModel.size()) {
            val param = paramListModel.getElementAt(i)
            sb.append("@param ")
            
            // Add tags if any
            if (param.tags.isNotEmpty()) {
                param.tags.forEach { tag ->
                    sb.append("{$tag}")
                }
                sb.append(" ")
            }
            
            sb.append(param.name)
            
            // Add description if any
            if (param.description.isNotEmpty()) {
                sb.append("\n").append(param.description)
            }
            
            sb.append("\n")
        }
        
        if (paramListModel.size() > 0) {
            sb.append("\n")
        }
        
        // Add returns
        for (i in 0 until returnListModel.size()) {
            val ret = returnListModel.getElementAt(i)
            sb.append("@return ${ret.name}")
            
            if (ret.description.isNotEmpty()) {
                sb.append("\n").append(ret.description)
            }
            
            sb.append("\n")
        }
        
        if (returnListModel.size() > 0) {
            sb.append("\n")
        }
        
        // Add snippet_before
        val snippetBefore = snippetBeforeTextArea.text.trim()
        if (snippetBefore.isNotEmpty()) {
            sb.append("@snippet_before\n").append(snippetBefore).append("\n\n")
        }
        
        // Add snippet_after
        val snippetAfter = snippetAfterTextArea.text.trim()
        if (snippetAfter.isNotEmpty()) {
            sb.append("@snippet_after\n").append(snippetAfter).append("\n")
        }
        
        return sb.toString().trimEnd()
    }

    // Data classes
    data class ParamEntry(
        val name: String,
        val tags: List<String>,
        val description: String
    )

    data class ReturnEntry(
        val name: String,
        val description: String
    )

    // Custom cell renderers
    private class ParamListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            component.font = Font("JetBrains Mono", Font.PLAIN, 12)
            component.border = EmptyBorder(5, 10, 5, 10)
            
            if (value is ParamEntry) {
                val tagsStr = if (value.tags.isNotEmpty()) {
                    " ${value.tags.joinToString(", ")}"
                } else ""
                val desc = if (value.description.isNotEmpty()) {
                    " — ${value.description.take(40)}"
                } else ""
                text = "<html><b>${value.name}</b>$tagsStr$desc</html>"
            }
            return component
        }
    }

    private class ReturnListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            component.font = Font("JetBrains Mono", Font.PLAIN, 12)
            component.border = EmptyBorder(5, 10, 5, 10)
            
            if (value is ReturnEntry) {
                val desc = if (value.description.isNotEmpty()) {
                    " — ${value.description.take(40)}"
                } else ""
                text = "<html><b>${value.name}</b>$desc</html>"
            }
            return component
        }
    }

    // Dialog for adding/editing parameters
    private inner class ParamEntryDialog(
        project: Project?,
        private val existingParam: ParamEntry?
    ) : DialogWrapper(project) {

        private val nameField = JBTextField(existingParam?.name ?: "", 30)
        private val tagsField = JBTextField(existingParam?.tags?.joinToString(", ") ?: "", 30)
        private val descArea = JTextArea(existingParam?.description ?: "", 3, 30)
        private var result: ParamEntry? = null

        init {
            title = if (existingParam != null) "Edit Parameter" else "Add Parameter"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = JBUI.insets(5)
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.anchor = GridBagConstraints.WEST
            
            // Name
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 0.0
            panel.add(JLabel("Parameter Name:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(nameField, gbc)
            
            // Tags
            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.0
            panel.add(JLabel("Tags (comma-separated):"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(tagsField, gbc)
            
            // Add hint for tags
            gbc.gridx = 1
            gbc.gridy = 2
            val hintLabel = JLabel("<html><i>Example: required, optional, input, predefined</i></html>")
            hintLabel.foreground = Color.GRAY
            panel.add(hintLabel, gbc)
            
            // Description
            gbc.gridx = 0
            gbc.gridy = 3
            gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.NORTHWEST
            panel.add(JLabel("Description:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            descArea.lineWrap = true
            descArea.wrapStyleWord = true
            panel.add(JBScrollPane(descArea), gbc)
            
            return panel
        }

        override fun doOKAction() {
            val name = nameField.text.trim()
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(contentPane, "Parameter name cannot be empty", "Error", JOptionPane.ERROR_MESSAGE)
                return
            }
            
            val tags = tagsField.text.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            
            result = ParamEntry(name, tags, descArea.text.trim())
            super.doOKAction()
        }

        fun getParamEntry(): ParamEntry? = result
    }

    // Dialog for adding/editing returns
    private inner class ReturnEntryDialog(
        project: Project?,
        private val existingReturn: ReturnEntry?
    ) : DialogWrapper(project) {

        private val nameField = JBTextField(existingReturn?.name ?: "", 30)
        private val descArea = JTextArea(existingReturn?.description ?: "", 3, 30)
        private var result: ReturnEntry? = null

        init {
            title = if (existingReturn != null) "Edit Return" else "Add Return"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = JBUI.insets(5)
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.anchor = GridBagConstraints.WEST
            
            // Name
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 0.0
            panel.add(JLabel("Return Name:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(nameField, gbc)
            
            // Description
            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.NORTHWEST
            panel.add(JLabel("Description:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            descArea.lineWrap = true
            descArea.wrapStyleWord = true
            panel.add(JBScrollPane(descArea), gbc)
            
            return panel
        }

        override fun doOKAction() {
            val name = nameField.text.trim()
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(contentPane, "Return name cannot be empty", "Error", JOptionPane.ERROR_MESSAGE)
                return
            }
            
            result = ReturnEntry(name, descArea.text.trim())
            super.doOKAction()
        }

        fun getReturnEntry(): ReturnEntry? = result
    }
}
