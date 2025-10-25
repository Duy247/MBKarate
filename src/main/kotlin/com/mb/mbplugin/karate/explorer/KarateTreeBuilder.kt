package com.mb.mbplugin.karate.explorer

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.mb.mbplugin.karate.documentation.KarateFeatureDocumentationProvider
import com.mb.mbplugin.karate.psi.KarateFile
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Builder for constructing the Karate feature tree structure
 */
class KarateTreeBuilder(private val project: Project) {

    private val parser = KarateTreeParser(project)
    private val visitedCallReads = mutableSetOf<String>() // To prevent infinite recursion
    private val docProvider = KarateFeatureDocumentationProvider()
    private var useShortDescriptions = false

    /**
     * Set whether to use short descriptions for call reads
     */
    fun setUseShortDescriptions(use: Boolean) {
        useShortDescriptions = use
    }

    /**
     * Build a tree model from a feature file
     */
    fun buildTreeModel(psiFile: PsiFile): DefaultTreeModel {
        visitedCallReads.clear()
        
        val fileName = psiFile.name
        val rootNode = DefaultMutableTreeNode(FeatureNode(fileName, psiFile))
        
        val scenarios = parser.parseFeatureFile(psiFile)
        
        for (scenario in scenarios) {
            val scenarioNode = DefaultMutableTreeNode(
                ScenarioNode(
                    displayName = buildScenarioDisplayName(scenario),
                    psiElement = scenario.psiElement,
                    tag = scenario.tag
                )
            )
            
            // Add steps to scenario
            for (step in scenario.steps) {
                val stepNode = DefaultMutableTreeNode(
                    StepNode(
                        displayName = step.text, // No truncation
                        psiElement = step.psiElement,
                        stepText = step.text
                    )
                )
                
                // Add call reads to step
                for (callRead in step.callReads) {
                    val callReadNode = buildCallReadNode(callRead, 0, psiFile)
                    stepNode.add(callReadNode)
                }
                
                scenarioNode.add(stepNode)
            }
            
            rootNode.add(scenarioNode)
        }
        
        return DefaultTreeModel(rootNode)
    }

    /**
     * Build display name for scenario
     */
    private fun buildScenarioDisplayName(scenario: KarateTreeParser.ScenarioInfo): String {
        return when {
            scenario.isBackground -> "Background${if (scenario.name.isNotEmpty()) ": ${scenario.name}" else ""}"
            scenario.isOutline -> "Scenario Outline: ${scenario.name}"
            else -> "Scenario: ${scenario.name}"
        }
    }

    /**
     * Build a call read node with its nested content
     */
    private fun buildCallReadNode(
        callRead: KarateTreeParser.CallReadInfo,
        depth: Int,
        currentPsiFile: PsiFile
    ): DefaultMutableTreeNode {
        // Create a unique key for this call read to detect cycles
        val callReadKey = "${callRead.featurePath}@${callRead.scenarioTag ?: ""}"
        
        // Prevent infinite recursion (max depth of 10 or if we've seen this before)
        if (depth > 10 || visitedCallReads.contains(callReadKey)) {
            return DefaultMutableTreeNode(
                CallReadNode(
                    displayName = buildCallReadDisplayName(callRead, currentPsiFile) + " (circular reference)",
                    psiElement = callRead.psiElement,
                    featurePath = callRead.featurePath,
                    scenarioTag = callRead.scenarioTag,
                    fullCallText = callRead.fullCallText
                )
            )
        }
        
        visitedCallReads.add(callReadKey)
        
        val callReadNode = DefaultMutableTreeNode(
            CallReadNode(
                displayName = buildCallReadDisplayName(callRead, currentPsiFile),
                psiElement = callRead.psiElement,
                featurePath = callRead.featurePath,
                scenarioTag = callRead.scenarioTag,
                fullCallText = callRead.fullCallText
            )
        )
        
        // Try to resolve the call read
        val resolved = parser.resolveCallRead(callRead)
        
        if (resolved != null) {
            // First, check if there's a Background in the feature file
            val background = resolved.allScenarios.firstOrNull { it.isBackground }
            
            if (resolved.scenarios.isNotEmpty()) {
                // Add background steps first if they exist AND we're calling a specific scenario (not the whole file)
                // If calling the whole file, background is already in resolved.scenarios
                if (background != null && callRead.scenarioTag != null) {
                    val backgroundNode = DefaultMutableTreeNode(
                        ScenarioNode(
                            displayName = buildScenarioDisplayName(background),
                            psiElement = background.psiElement,
                            tag = background.tag
                        )
                    )
                    
                    // Add the background's steps
                    addScenarioSteps(backgroundNode, background, depth + 1, currentPsiFile)
                    
                    callReadNode.add(backgroundNode)
                }
                
                // Add each resolved scenario as a child node
                for (scenario in resolved.scenarios) {
                    val scenarioNode = DefaultMutableTreeNode(
                        ScenarioNode(
                            displayName = buildScenarioDisplayName(scenario),
                            psiElement = scenario.psiElement,
                            tag = scenario.tag
                        )
                    )
                    
                    // Add the scenario's steps
                    addScenarioSteps(scenarioNode, scenario, depth + 1, currentPsiFile)
                    
                    callReadNode.add(scenarioNode)
                }
            } else {
                // No scenarios found with the specified tag
                callReadNode.add(DefaultMutableTreeNode(
                    ErrorNode(
                        displayName = if (callRead.scenarioTag != null) {
                            "Scenario not found: @${callRead.scenarioTag}"
                        } else {
                            "No scenarios found in file"
                        },
                        errorMessage = "The scenario tag '${callRead.scenarioTag}' was not found in ${callRead.featurePath}"
                    )
                ))
            }
        } else {
            // File not found
            callReadNode.add(DefaultMutableTreeNode(
                ErrorNode(
                    displayName = "Feature file not found",
                    errorMessage = "Could not locate file: ${callRead.featurePath}"
                )
            ))
        }
        
        visitedCallReads.remove(callReadKey)
        
        return callReadNode
    }

    /**
     * Add scenario steps to a parent node (used for call read expansion)
     */
    private fun addScenarioSteps(
        parentNode: DefaultMutableTreeNode,
        scenario: KarateTreeParser.ScenarioInfo,
        depth: Int,
        currentPsiFile: PsiFile
    ) {
        for (step in scenario.steps) {
            val stepNode = DefaultMutableTreeNode(
                StepNode(
                    displayName = step.text, // No truncation
                    psiElement = step.psiElement,
                    stepText = step.text
                )
            )
            
            // Add nested call reads
            for (callRead in step.callReads) {
                val nestedCallReadNode = buildCallReadNode(callRead, depth, currentPsiFile)
                stepNode.add(nestedCallReadNode)
            }
            
            parentNode.add(stepNode)
        }
    }

    /**
     * Build display name for call read
     */
    private fun buildCallReadDisplayName(callRead: KarateTreeParser.CallReadInfo, currentPsiFile: PsiFile): String {
        // If short descriptions are enabled and we have a scenario tag, try to get @short
        if (useShortDescriptions && callRead.scenarioTag != null) {
            val shortDesc = getShortDescription(callRead.featurePath, callRead.scenarioTag, currentPsiFile)
            if (shortDesc != null) {
                return "* $shortDesc" // No truncation
            }
        }
        
        // Default display - no truncation
        val fileName = callRead.featurePath.substringAfterLast('/')
        return if (callRead.scenarioTag != null) {
            "* call read '$fileName@${callRead.scenarioTag}'"
        } else {
            "* call read '$fileName'"
        }
    }

    /**
     * Get @short description for a scenario (cached from current file to avoid EDT issues)
     */
    private fun getShortDescription(featurePath: String, scenarioTag: String, currentPsiFile: PsiFile): String? {
        try {
            // Find the feature file by resolving it through the parser
            val resolved = parser.resolveCallRead(
                KarateTreeParser.CallReadInfo(
                    featurePath = featurePath,
                    scenarioTag = scenarioTag,
                    psiElement = currentPsiFile,
                    fullCallText = ""
                )
            ) ?: return null
            
            val psiFile = resolved.psiFile as? KarateFile ?: return null
            
            // Extract documentation for the scenario tag
            val docText = docProvider.extractDocumentationFromFile(psiFile, scenarioTag) ?: return null
            
            // Parse to get @short
            val parsed = docProvider.parseDocDirectives(docText)
            return parsed.shortDescription
        } catch (e: Exception) {
            return null
        }
    }
}
