package com.mb.mbplugin.karate.explorer

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Builder for constructing the Karate feature tree structure
 */
class KarateTreeBuilder(private val project: Project) {

    private val parser = KarateTreeParser(project)
    private val visitedCallReads = mutableSetOf<String>() // To prevent infinite recursion

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
                        displayName = truncateText(step.text, 80),
                        psiElement = step.psiElement,
                        stepText = step.text
                    )
                )
                
                // Add call reads to step
                for (callRead in step.callReads) {
                    val callReadNode = buildCallReadNode(callRead, 0)
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
        depth: Int
    ): DefaultMutableTreeNode {
        // Create a unique key for this call read to detect cycles
        val callReadKey = "${callRead.featurePath}@${callRead.scenarioTag ?: ""}"
        
        // Prevent infinite recursion (max depth of 10 or if we've seen this before)
        if (depth > 10 || visitedCallReads.contains(callReadKey)) {
            return DefaultMutableTreeNode(
                CallReadNode(
                    displayName = buildCallReadDisplayName(callRead) + " (circular reference)",
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
                displayName = buildCallReadDisplayName(callRead),
                psiElement = callRead.psiElement,
                featurePath = callRead.featurePath,
                scenarioTag = callRead.scenarioTag,
                fullCallText = callRead.fullCallText
            )
        )
        
        // Try to resolve the call read
        val resolved = parser.resolveCallRead(callRead)
        
        if (resolved != null) {
            if (resolved.scenarios.isNotEmpty()) {
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
                    addScenarioSteps(scenarioNode, scenario, depth + 1)
                    
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
        depth: Int
    ) {
        for (step in scenario.steps) {
            val stepNode = DefaultMutableTreeNode(
                StepNode(
                    displayName = truncateText(step.text, 80),
                    psiElement = step.psiElement,
                    stepText = step.text
                )
            )
            
            // Add nested call reads
            for (callRead in step.callReads) {
                val nestedCallReadNode = buildCallReadNode(callRead, depth)
                stepNode.add(nestedCallReadNode)
            }
            
            parentNode.add(stepNode)
        }
    }

    /**
     * Build display name for call read
     */
    private fun buildCallReadDisplayName(callRead: KarateTreeParser.CallReadInfo): String {
        val fileName = callRead.featurePath.substringAfterLast('/')
        return if (callRead.scenarioTag != null) {
            "* call read '$fileName@${callRead.scenarioTag}'"
        } else {
            "* call read '$fileName'"
        }
    }

    /**
     * Truncate text to a maximum length
     */
    private fun truncateText(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.substring(0, maxLength) + "..."
        } else {
            text
        }
    }
}
