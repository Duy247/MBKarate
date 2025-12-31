package com.mb.mbplugin.karate.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.mb.mbplugin.settings.KarateVariableSuggestionSettings
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinLanguage

/**
 * Completion contributor for Karate variable names
 * Provides autocomplete suggestions for variables defined via "* def" statements
 */
class KarateVariableCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(GherkinLanguage.INSTANCE),
            KarateVariableCompletionProvider()
        )
    }
}

class KarateVariableCompletionProvider : CompletionProvider<CompletionParameters>() {
    
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val file = parameters.originalFile as? GherkinFile ?: return
        val project = file.project
        
        // Check if variable suggestion is enabled
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        if (!settings.isEnabled) return
        
        val offset = parameters.offset
        val extractor = KarateVariableExtractor.getInstance(project)
        
        // Find the scenario containing the cursor
        val scenario = extractor.findScenarioAtOffset(file, offset) ?: return
        
        // Extract all available variables
        val variables = extractor.extractVariablesForScenario(scenario, offset)
        
        // Add each variable as a completion suggestion with prefix matching
        // This allows variables to appear even when typing any text
        val prefixMatcher = result.prefixMatcher
        variables.forEach { varInfo ->
            // Build the type text showing where the variable was defined
            val typeText = if (varInfo.scenarioTag != null) {
                "${varInfo.fileName}@${varInfo.scenarioTag}"
            } else {
                varInfo.fileName
            }
            
            // Always add the variable - IntelliJ will filter based on what user types
            result.addElement(
                LookupElementBuilder.create(varInfo.name)
                    .withIcon(AllIcons.Nodes.Variable)
                    .withTypeText(typeText, true)
                    .withCaseSensitivity(false) // Case-insensitive matching
                    .withInsertHandler { ctx, _ ->
                        // Simple insert - just place the variable name
                        val document = ctx.document
                        val startOffset = ctx.startOffset
                        val tailOffset = ctx.tailOffset
                        
                        document.replaceString(startOffset, tailOffset, varInfo.name)
                        ctx.editor.caretModel.moveToOffset(startOffset + varInfo.name.length)
                    }
            )
        }
        
        // Stop here to prevent other completions from interfering
        result.stopHere()
    }
}
