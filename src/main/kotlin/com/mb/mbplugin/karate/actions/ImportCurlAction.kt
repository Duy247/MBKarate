package com.mb.mbplugin.karate.actions

import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.EditorTextFieldProvider
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import javax.swing.SwingUtilities



class ImportCurlAction : AnAction("Import cURL as Karate Feature") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document

        val dialog = CurlInputDialog(project)
        if (!dialog.showAndGet()) return

        val curlInput = dialog.getCurlText()
        if (curlInput.isBlank()) return

        val featureText = CurlToKarateConverter().convert(curlInput)
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(featureText)
        }
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible =
            psiFile?.virtualFile?.extension.equals("feature", ignoreCase = true)
    }
}

private class CurlInputDialog(project: Project) : DialogWrapper(project) {
    private val curlField = EditorTextField("",project,com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE)

    init {
        title = "Import cURL"
        setSize(800, 400)
        init()

        curlField.setOneLineMode(false)
        curlField.font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 13)
        curlField.preferredSize = JBUI.size(750, 250)
        var timer: Timer? = null
        curlField.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                timer?.cancel()
                timer = Timer()
                timer!!.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        formatCurl()
                    }
                }, 500)
            }
        }, disposable)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        panel.add(JBLabel("Paste your cURL command below:"), BorderLayout.NORTH)
        panel.add(curlField, BorderLayout.CENTER)

        SwingUtilities.invokeLater {
            curlField.editor?.settings?.apply {
                isUseSoftWraps = true
                isLineMarkerAreaShown = false
                isVirtualSpace = false
                isAdditionalPageAtBottom = false
            }
        }

        return panel
    }

    private fun formatCurl() {
        val text = curlField.text
        val formatted = text
            .replace("\\\\[\\r\\n]*".toRegex(), "\\\\\n") // ensure each \ ends with newline
            .replace("\\s+".toRegex(), " ")
            .replace("\\\\\\s+".toRegex(), "\\\\\n") // ensure a space after \ becomes newline
            .trim()
        if (formatted != text) {
            SwingUtilities.invokeLater {
                curlField.text = formatted
            }
        }
    }


    fun getCurlText(): String = curlField.text.trim()
}



