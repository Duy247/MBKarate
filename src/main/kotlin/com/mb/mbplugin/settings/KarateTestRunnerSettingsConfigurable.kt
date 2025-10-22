package com.mb.mbplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.*

class KarateTestRunnerSettingsConfigurable(private val project: Project) : Configurable {
    private var configCombo: JComboBox<String>? = null
    private var filePathField: JBTextField? = null
    private var templateArea: JTextArea? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName() = "Karate Test Runner"

    override fun createComponent(): JComponent {
        val settings = KarateTestRunnerSettings.getInstance(project)

        configCombo = JComboBox<String>().apply {
            settings.state.configs.forEach { addItem(it.name) }
            selectedItem = settings.state.currentConfig
            addActionListener {
                val name = selectedItem as String
                settings.setCurrentConfigName(name)
                val cfg = settings.getCurrentConfig()
                filePathField?.text = cfg.testRunnerFilePath
                templateArea?.text = cfg.testRunnerTemplate
            }
        }

        val addBtn = JButton("Add").apply {
            toolTipText = "Add new configuration"
            addActionListener {
                val name = JOptionPane.showInputDialog("Enter configuration name:")
                if (!name.isNullOrBlank()) {
                    settings.addConfig(name)
                    configCombo?.addItem(name)
                    configCombo?.selectedItem = name
                }
            }
        }

        val removeBtn = JButton("Remove").apply {
            toolTipText = "Remove selected configuration"
            addActionListener {
                val name = configCombo?.selectedItem as? String ?: return@addActionListener
                if (name != "Default") {
                    settings.removeConfig(name)
                    configCombo?.removeItem(name)
                    configCombo?.selectedItem = settings.state.currentConfig
                } else {
                    JOptionPane.showMessageDialog(null, "Default config cannot be removed.")
                }
            }
        }

        filePathField = JBTextField()
        templateArea = JTextArea(25, 40).apply {
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
            tabSize = 4
        }

        val scrollPane = JBScrollPane(templateArea).apply {
            preferredSize = Dimension(300, 200)
        }

        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Configuration: "))
            add(configCombo)
            add(Box.createHorizontalStrut(5))
            add(addBtn)
            add(removeBtn)
        }

        val helpText = JBLabel(
            """
                <html>
                <b>Available template variables:</b><br>
                {{PACKAGE_NAME}} – Java package name derived from file path<br>
                {{CLASS_NAME}} – Java class name derived from file name<br>
                {{FILE_PATH}} – Relative path to the feature file<br>
                {{TAG_TEXT}} – The selected tag text<br><br>
                <i>Tip: Use standard Java syntax with these placeholders.</i>
                </html>
            """.trimIndent()
        )

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(toolbar)
            .addLabeledComponent(JBLabel("Test Runner File Path:"), filePathField!!)
            .addLabeledComponent(JBLabel("Test Runner Template:"), scrollPane)
            .addComponent(helpText)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = KarateTestRunnerSettings.getInstance(project)
        val cfg = settings.getCurrentConfig()
        return filePathField?.text != cfg.testRunnerFilePath ||
               templateArea?.text != cfg.testRunnerTemplate
    }

    override fun apply() {
        val settings = KarateTestRunnerSettings.getInstance(project)
        val cfg = settings.getCurrentConfig()
        cfg.testRunnerFilePath = filePathField?.text ?: ""
        cfg.testRunnerTemplate = templateArea?.text ?: ""
    }

    override fun reset() {
        val settings = KarateTestRunnerSettings.getInstance(project)
        val cfg = settings.getCurrentConfig()
        filePathField?.text = cfg.testRunnerFilePath
        templateArea?.text = cfg.testRunnerTemplate
        configCombo?.selectedItem = settings.state.currentConfig
    }

    override fun disposeUIResources() {
        mainPanel = null
        configCombo = null
        filePathField = null
        templateArea = null
    }
}
