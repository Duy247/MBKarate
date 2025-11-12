package com.mb.mbplugin.karate.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.mb.mbplugin.karate.explorer.KarateTreeParser
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinScenario
import org.jetbrains.plugins.cucumber.psi.GherkinScenarioOutline
import javax.swing.JComponent

/**
 * Action to help create documentation for Karate scenarios
 */
class DocumentHelperAction : AnAction("Document Helper") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? GherkinFile ?: return

        // Parse scenarios with tags
        val parser = KarateTreeParser(project)
        val scenarios = parser.parseFeatureFile(psiFile)
            .filter { !it.isBackground && it.tag != null }

        if (scenarios.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No scenarios with tags found in this feature file.",
                "Document Helper"
            )
            return
        }

        // Show scenario selection dialog
        val selectionDialog = ScenarioSelectionDialog(project, scenarios)
        if (!selectionDialog.showAndGet()) return

        val selectedScenario = selectionDialog.getSelectedScenario() ?: return

        // Check if existing documentation exists
        val existingDoc = findExistingDocumentation(psiFile, selectedScenario)

        // Show documentation builder dialog
        val docBuilderDialog = DocumentationBuilderDialog(project, selectedScenario.tag!!, existingDoc)
        if (!docBuilderDialog.showAndGet()) return

        val documentation = docBuilderDialog.buildDocumentation()

        // Insert or replace documentation in the scenario
        insertOrReplaceDocumentation(project, psiFile, selectedScenario, documentation, existingDoc)
    }

    data class ExistingDocumentation(
        val startOffset: Int,
        val endOffset: Int,
        val content: String
    )

    private fun findExistingDocumentation(
        psiFile: GherkinFile,
        scenario: KarateTreeParser.ScenarioInfo
    ): ExistingDocumentation? {
        val scenarioElement = scenario.psiElement
        val scenarioText = scenarioElement.text
        
        // Look for """ @TAG_DOC ... """
        val docPattern = Regex(
            "\"\"\"\\s*@${scenario.tag}_DOC\\s*\\n([\\s\\S]*?)\"\"\"",
            RegexOption.MULTILINE
        )
        
        val match = docPattern.find(scenarioText) ?: return null
        
        val startInElement = match.range.first
        val endInElement = match.range.last + 1
        
        val absoluteStart = scenarioElement.textOffset + startInElement
        val absoluteEnd = scenarioElement.textOffset + endInElement
        
        return ExistingDocumentation(
            startOffset = absoluteStart,
            endOffset = absoluteEnd,
            content = match.groupValues[1].trim()
        )
    }

    private fun insertOrReplaceDocumentation(
        project: com.intellij.openapi.project.Project,
        psiFile: GherkinFile,
        scenario: KarateTreeParser.ScenarioInfo,
        documentation: String,
        existingDoc: ExistingDocumentation?
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runWriteCommandAction
            
            val docText = "\"\"\" @${scenario.tag}_DOC\n$documentation\n\"\"\"\n"
            
            if (existingDoc != null) {
                // Replace existing documentation
                document.replaceString(existingDoc.startOffset, existingDoc.endOffset, docText.trimEnd())
            } else {
                // Insert new documentation
                val scenarioElement = scenario.psiElement
                val text = document.text
                
                // Find the "Scenario:" or "Scenario Outline:" keyword within the scenario element
                val scenarioText = scenarioElement.text
                val scenarioKeywordPattern = if (scenario.isOutline) {
                    Regex("(?i)Scenario\\s+Outline\\s*:")
                } else {
                    Regex("(?i)Scenario\\s*:")
                }
                
                val matchInElement = scenarioKeywordPattern.find(scenarioText)
                if (matchInElement == null) {
                    return@runWriteCommandAction
                }
                
                // Calculate the absolute offset of the Scenario: keyword
                val keywordStartInElement = matchInElement.range.first
                val keywordAbsoluteOffset = scenarioElement.textOffset + keywordStartInElement
                
                // Find the end of the Scenario: line (first newline after the keyword)
                var insertOffset = keywordAbsoluteOffset
                while (insertOffset < text.length && text[insertOffset] != '\n') {
                    insertOffset++
                }
                
                // Move past the newline
                if (insertOffset < text.length && text[insertOffset] == '\n') {
                    insertOffset++
                }
                
                // Insert the documentation after the Scenario: line
                document.insertString(insertOffset, docText)
            }
            
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = psiFile is GherkinFile
    }
}
