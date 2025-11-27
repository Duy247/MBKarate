package com.mb.mbplugin.karate.completion

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.mb.mbplugin.karate.explorer.KarateTreeParser
import com.mb.mbplugin.settings.KarateVariableSuggestionSettings
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinScenario
import org.jetbrains.plugins.cucumber.psi.GherkinScenarioOutline
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import java.util.regex.Pattern

/**
 * Service for extracting variable definitions from Karate feature files
 * Handles def statements and traverses call read chains to find all available variables
 */
class KarateVariableExtractor(private val project: Project) {

    private val parser = KarateTreeParser(project)
    
    companion object {
        // Maximum depth to prevent infinite recursion
        private const val MAX_DEPTH = 10
    }
    
    /**
     * Get compiled patterns from settings
     */
    private fun getPatterns(): List<Pattern> {
        val settings = KarateVariableSuggestionSettings.getInstance(project)
        return settings.patterns.mapNotNull { patternStr ->
            try {
                Pattern.compile(patternStr, Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)
            } catch (e: Exception) {
                null // Skip invalid patterns
            }
        }
    }

    data class VariableInfo(
        val name: String,
        val fileName: String,
        val scenarioTag: String?
    )

    /**
     * Extract all variable definitions available at a given position in a scenario
     * This includes variables from:
     * - Background (if present)
     * - The scenario itself (up to the given step)
     * - All call read statements (recursively)
     * - Variables from calling scenarios (reverse lookup)
     */
    fun extractVariablesForScenario(
        scenario: PsiElement,
        currentStepOffset: Int
    ): Set<VariableInfo> {
        val variables = mutableSetOf<VariableInfo>()
        val visitedFiles = mutableSetOf<String>()
        
        // Get the containing file
        val featureFile = scenario.containingFile as? GherkinFile ?: return emptySet()
        val currentFileName = featureFile.name
        
        // Get the current scenario tag
        val currentScenarioTag = extractScenarioTag(scenario)
        
        // First, try to find calling scenarios (reverse lookup)
        findCallingScenarios(featureFile, currentScenarioTag, variables, visitedFiles)
        
        // Then collect variables from Background
        val background = PsiTreeUtil.findChildrenOfType(featureFile, GherkinScenario::class.java)
            .firstOrNull { it.firstChild?.text?.trim()?.lowercase()?.startsWith("background") == true }
        
        if (background != null) {
            collectVariablesFromScenario(background, Int.MAX_VALUE, variables, visitedFiles, 0, currentFileName, "Background")
        }
        
        // Then collect from the current scenario (up to current step)
        collectVariablesFromScenario(scenario, currentStepOffset, variables, visitedFiles, 0, currentFileName, currentScenarioTag)
        
        return variables
    }
    
    /**
     * Find scenarios in other files that call this scenario
     * and collect variables defined before the call read statement
     */
    private fun findCallingScenarios(
        targetFile: GherkinFile,
        targetScenarioTag: String?,
        variables: MutableSet<VariableInfo>,
        visitedFiles: MutableSet<String>
    ) {
        val projectBasePath = project.basePath ?: return
        val targetFileName = targetFile.name
        val targetPath = targetFile.virtualFile?.path ?: return
        
        // Search for call read statements that reference this file/scenario
        val allFeatureFiles = com.intellij.psi.search.FileTypeIndex.getFiles(
            org.jetbrains.plugins.cucumber.psi.GherkinFileType.INSTANCE,
            com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        )
        
        for (vFile in allFeatureFiles) {
            if (vFile.path == targetPath) continue // Skip the current file
            
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile) as? GherkinFile ?: continue
            val fileText = psiFile.text
            
            // Check if this file calls our target
            val callPattern = if (targetScenarioTag != null) {
                Pattern.compile("call\\s+read\\s*\\([^)]*${Pattern.quote(targetFileName)}[^)]*@${Pattern.quote(targetScenarioTag)}[^)]*\\)", Pattern.CASE_INSENSITIVE)
            } else {
                Pattern.compile("call\\s+read\\s*\\([^)]*${Pattern.quote(targetFileName)}[^)]*\\)", Pattern.CASE_INSENSITIVE)
            }
            
            if (!callPattern.matcher(fileText).find()) continue
            
            // Found a calling file - collect variables from scenarios that call us
            val scenarios = PsiTreeUtil.findChildrenOfType(psiFile, GherkinScenario::class.java).toList() +
                           PsiTreeUtil.findChildrenOfType(psiFile, GherkinScenarioOutline::class.java).toList()
            
            for (callingScenario in scenarios) {
                // Check if this scenario has the call read
                val steps = PsiTreeUtil.findChildrenOfType(callingScenario, GherkinStep::class.java)
                var foundCallRead = false
                var callReadOffset = -1
                
                for (step in steps) {
                    if (callPattern.matcher(step.text ?: "").find()) {
                        foundCallRead = true
                        callReadOffset = step.textOffset
                        break
                    }
                }
                
                if (foundCallRead) {
                    // Collect variables from this calling scenario up to the call read point
                    val callingFileName = psiFile.name
                    val callingScenarioTag = extractScenarioTag(callingScenario)
                    
                    // Add background variables from calling file
                    val callingBackground = PsiTreeUtil.findChildrenOfType(psiFile, GherkinScenario::class.java)
                        .firstOrNull { it.firstChild?.text?.trim()?.lowercase()?.startsWith("background") == true }
                    
                    if (callingBackground != null) {
                        collectVariablesFromScenario(callingBackground, Int.MAX_VALUE, variables, visitedFiles, 0, callingFileName, "Background")
                    }
                    
                    // Collect variables up to the call read point
                    collectVariablesFromScenario(callingScenario, callReadOffset, variables, visitedFiles, 0, callingFileName, callingScenarioTag)
                }
            }
        }
    }
    
    private fun extractScenarioTag(scenario: PsiElement): String? {
        return when (scenario) {
            is GherkinScenario -> {
                val tags = PsiTreeUtil.findChildrenOfType(scenario, org.jetbrains.plugins.cucumber.psi.GherkinTag::class.java)
                tags.firstOrNull()?.name?.removePrefix("@")
            }
            is GherkinScenarioOutline -> {
                val tags = PsiTreeUtil.findChildrenOfType(scenario, org.jetbrains.plugins.cucumber.psi.GherkinTag::class.java)
                tags.firstOrNull()?.name?.removePrefix("@")
            }
            else -> null
        }
    }

    /**
     * Collect variables from a scenario, including all nested call reads
     */
    private fun collectVariablesFromScenario(
        scenario: PsiElement,
        stopOffset: Int,
        variables: MutableSet<VariableInfo>,
        visitedFiles: MutableSet<String>,
        depth: Int,
        fileName: String,
        scenarioTag: String?
    ) {
        if (depth > MAX_DEPTH) return
        
        val steps = when (scenario) {
            is GherkinScenario -> PsiTreeUtil.findChildrenOfType(scenario, GherkinStep::class.java)
            is GherkinScenarioOutline -> PsiTreeUtil.findChildrenOfType(scenario, GherkinStep::class.java)
            else -> return
        }
        
        for (step in steps) {
            // Stop if we've passed the current cursor position
            if (step.textOffset > stopOffset) break
            
            val stepText = step.text ?: continue
            
            // Extract def statements from this step
            extractDefStatements(stepText, variables, fileName, scenarioTag)
            
            // Extract and resolve call reads
            val callReads = extractCallReadsFromStep(stepText, step)
            for (callRead in callReads) {
                resolveAndCollectVariables(callRead, variables, visitedFiles, depth + 1)
            }
        }
    }

    /**
     * Extract def statements from step text using patterns from settings
     */
    private fun extractDefStatements(text: String, variables: MutableSet<VariableInfo>, fileName: String, scenarioTag: String?) {
        val patterns = getPatterns()
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val varName = matcher.group(1)
                if (varName.isNotEmpty()) {
                    variables.add(VariableInfo(varName, fileName, scenarioTag))
                    
                    // Check if this is a JSON object definition (pattern ends with \{)
                    if (matcher.group(0).trimEnd().endsWith("{")) {
                        // Extract all JSON properties from the rest of the line
                        val jsonStart = matcher.end()
                        val restOfText = text.substring(jsonStart)
                        extractJsonProperties(varName, restOfText, variables, fileName, scenarioTag)
                    }
                }
            }
        }
    }
    
    /**
     * Extract property names from JSON object definition
     */
    private fun extractJsonProperties(
        varName: String,
        jsonText: String,
        variables: MutableSet<VariableInfo>,
        fileName: String,
        scenarioTag: String?
    ) {
        // Match quoted property names in JSON
        val propertyPattern = Pattern.compile("\"([a-zA-Z_][a-zA-Z0-9_]*)\"\\s*:")
        val matcher = propertyPattern.matcher(jsonText)
        
        while (matcher.find()) {
            val propName = matcher.group(1)
            if (propName.isNotEmpty()) {
                variables.add(VariableInfo("$varName.$propName", fileName, scenarioTag))
            }
        }
    }

    /**
     * Extract call read statements from a step
     */
    private fun extractCallReadsFromStep(
        stepText: String,
        stepElement: PsiElement
    ): List<KarateTreeParser.CallReadInfo> {
        val callReads = mutableListOf<KarateTreeParser.CallReadInfo>()
        val callReadPattern = Pattern.compile(
            "call\\s+read\\s*\\(\\s*['\"](?:classpath:|src/)?([^'\"]+\\.feature)(?:@(\\w+))?['\"]",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = callReadPattern.matcher(stepText)

        while (matcher.find()) {
            val featurePath = matcher.group(1)
            val scenarioTag = matcher.group(2)

            callReads.add(
                KarateTreeParser.CallReadInfo(
                    featurePath = featurePath,
                    scenarioTag = scenarioTag,
                    psiElement = stepElement,
                    fullCallText = matcher.group()
                )
            )
        }

        return callReads
    }

    /**
     * Resolve a call read and collect variables from the referenced file/scenario
     */
    private fun resolveAndCollectVariables(
        callRead: KarateTreeParser.CallReadInfo,
        variables: MutableSet<VariableInfo>,
        visitedFiles: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        
        // Create a unique key to detect cycles
        val fileKey = "${callRead.featurePath}@${callRead.scenarioTag ?: ""}"
        if (visitedFiles.contains(fileKey)) return
        visitedFiles.add(fileKey)
        
        // Resolve the call read using the parser
        val resolved = parser.resolveCallRead(callRead) ?: return
        
        val fileName = resolved.psiFile.name
        
        // First, process Background if it exists (not already included in resolved.scenarios when calling specific scenario)
        val background = resolved.allScenarios.firstOrNull { it.isBackground }
        if (background != null && callRead.scenarioTag != null) {
            collectVariablesFromResolvedScenario(background, variables, visitedFiles, depth, fileName, "Background")
        }
        
        // Process the referenced scenarios
        for (scenario in resolved.scenarios) {
            collectVariablesFromResolvedScenario(scenario, variables, visitedFiles, depth, fileName, scenario.tag)
        }
        
        visitedFiles.remove(fileKey)
    }

    /**
     * Collect variables from a resolved scenario info
     */
    private fun collectVariablesFromResolvedScenario(
        scenarioInfo: KarateTreeParser.ScenarioInfo,
        variables: MutableSet<VariableInfo>,
        visitedFiles: MutableSet<String>,
        depth: Int,
        fileName: String,
        scenarioTag: String?
    ) {
        for (step in scenarioInfo.steps) {
            // Extract def statements
            extractDefStatements(step.text, variables, fileName, scenarioTag)
            
            // Recursively process nested call reads
            for (nestedCallRead in step.callReads) {
                resolveAndCollectVariables(nestedCallRead, variables, visitedFiles, depth + 1)
            }
        }
    }

    /**
     * Find the scenario that contains the given offset
     */
    fun findScenarioAtOffset(file: GherkinFile, offset: Int): PsiElement? {
        // Find all scenarios (including scenario outlines)
        val scenarios = PsiTreeUtil.findChildrenOfType(file, GherkinScenario::class.java).toList() +
                       PsiTreeUtil.findChildrenOfType(file, GherkinScenarioOutline::class.java).toList()
        
        for (scenario in scenarios) {
            val scenarioStart = scenario.textOffset
            val scenarioEnd = scenarioStart + scenario.textLength
            
            // Check if offset is within this scenario
            if (offset >= scenarioStart && offset <= scenarioEnd) {
                // Skip if it's a background - we want the actual scenario
                val keyword = scenario.firstChild?.text?.trim()?.lowercase()
                if (keyword != "background" && keyword != "background:") {
                    return scenario
                }
            }
        }
        
        return null
    }
}
