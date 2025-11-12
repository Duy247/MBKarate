package com.mb.mbplugin.karate.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.mb.mbplugin.karate.explorer.KarateTreeParser
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Dialog for selecting a scenario from a list
 */
class ScenarioSelectionDialog(
    project: Project,
    private val scenarios: List<KarateTreeParser.ScenarioInfo>
) : DialogWrapper(project) {

    private val scenarioList: JBList<String>
    private var selectedScenario: KarateTreeParser.ScenarioInfo? = null

    init {
        title = "Select Scenario to Document"
        
        // Create list model with scenario display names
        val displayNames = scenarios.map { scenario ->
            val type = if (scenario.isOutline) "[Outline]" else ""
            "@${scenario.tag} - ${scenario.name} $type".trim()
        }
        
        scenarioList = JBList(displayNames)
        scenarioList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        scenarioList.selectedIndex = 0
        
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 5))
        panel.border = JBUI.Borders.empty(10)
        
        val label = JLabel("Select a scenario with a tag to create documentation:")
        panel.add(label, BorderLayout.NORTH)
        
        val scrollPane = JBScrollPane(scenarioList)
        scrollPane.preferredSize = Dimension(500, 300)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }

    override fun doOKAction() {
        val selectedIndex = scenarioList.selectedIndex
        if (selectedIndex >= 0 && selectedIndex < scenarios.size) {
            selectedScenario = scenarios[selectedIndex]
        }
        super.doOKAction()
    }

    fun getSelectedScenario(): KarateTreeParser.ScenarioInfo? = selectedScenario
}
