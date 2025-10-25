package com.mb.mbplugin.karate.explorer

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.cucumber.psi.*
import java.util.regex.Pattern

/**
 * Parser for extracting Karate feature file structure and call read statements
 */
class KarateTreeParser(private val project: Project) {

    companion object {
        private val CALL_READ_PATTERN = Pattern.compile(
            "call\\s+read\\s*\\(\\s*['\"](?:classpath:|src/)?([^'\"]+\\.feature)(?:@(\\w+))?['\"]",
            Pattern.CASE_INSENSITIVE
        )
    }

    /**
     * Parse a Gherkin file and extract scenarios (including Scenario Outlines and Backgrounds)
     */
    fun parseFeatureFile(psiFile: PsiFile): List<ScenarioInfo> {
        if (psiFile !is GherkinFile) return emptyList()

        val scenarios = mutableListOf<ScenarioInfo>()
        
        // Find both regular scenarios and scenario outlines
        val gherkinScenarios = PsiTreeUtil.findChildrenOfType(psiFile, GherkinScenario::class.java)
        val gherkinScenarioOutlines = PsiTreeUtil.findChildrenOfType(psiFile, GherkinScenarioOutline::class.java)

        // Process regular scenarios (including backgrounds)
        for (scenario in gherkinScenarios) {
            val keyword = scenario.firstChild?.text?.trim()?.lowercase()
            val isBackground = keyword == "background" || keyword == "background:"
            
            val scenarioName = scenario.scenarioName ?: if (isBackground) "" else "Unnamed Scenario"
            val tag = if (!isBackground) extractScenarioTag(scenario) else null
            val steps = extractSteps(scenario)

            scenarios.add(
                ScenarioInfo(
                    name = scenarioName,
                    tag = tag,
                    psiElement = scenario,
                    steps = steps,
                    isOutline = false,
                    isBackground = isBackground
                )
            )
        }

        // Process scenario outlines
        for (outline in gherkinScenarioOutlines) {
            val scenarioName = outline.scenarioName ?: "Unnamed Scenario Outline"
            val tag = extractScenarioOutlineTag(outline)
            val steps = extractOutlineSteps(outline)

            scenarios.add(
                ScenarioInfo(
                    name = scenarioName,
                    tag = tag,
                    psiElement = outline,
                    steps = steps,
                    isOutline = true,
                    isBackground = false
                )
            )
        }

        return scenarios
    }

    /**
     * Extract tag from a scenario (tags that appear directly before the scenario)
     */
    private fun extractScenarioTag(scenario: GherkinScenario): String? {
        // First try to find tags as children
        var tags = PsiTreeUtil.findChildrenOfType(scenario, GherkinTag::class.java).toList()
        
        // If no tags found as children, look for previous siblings
        if (tags.isEmpty()) {
            var prevSibling = scenario.prevSibling
            while (prevSibling != null) {
                if (prevSibling is GherkinTag) {
                    tags = listOf(prevSibling)
                    break
                }
                // Stop if we hit another scenario or feature
                if (prevSibling is GherkinScenario || prevSibling.text.trim().startsWith("Feature:")) {
                    break
                }
                prevSibling = prevSibling.prevSibling
            }
        }
        
        return tags.firstOrNull()?.name?.removePrefix("@")
    }

    /**
     * Extract tag from a scenario outline (tags that appear directly before the scenario outline)
     */
    private fun extractScenarioOutlineTag(outline: GherkinScenarioOutline): String? {
        // First try to find tags as children
        var tags = PsiTreeUtil.findChildrenOfType(outline, GherkinTag::class.java).toList()
        
        // If no tags found as children, look for previous siblings
        if (tags.isEmpty()) {
            var prevSibling = outline.prevSibling
            while (prevSibling != null) {
                if (prevSibling is GherkinTag) {
                    tags = listOf(prevSibling)
                    break
                }
                // Stop if we hit another scenario or feature
                if (prevSibling is GherkinScenario || 
                    prevSibling is GherkinScenarioOutline || 
                    prevSibling.text.trim().startsWith("Feature:")) {
                    break
                }
                prevSibling = prevSibling.prevSibling
            }
        }
        
        return tags.firstOrNull()?.name?.removePrefix("@")
    }

    /**
     * Extract all steps from a scenario
     */
    private fun extractSteps(scenario: GherkinScenario): List<StepInfo> {
        val steps = PsiTreeUtil.findChildrenOfType(scenario, GherkinStep::class.java)
        return steps.mapNotNull { step ->
            val stepText = step.text?.trim() ?: return@mapNotNull null
            val callReads = extractCallReads(stepText, step)

            StepInfo(
                text = stepText,
                psiElement = step,
                callReads = callReads
            )
        }
    }

    /**
     * Extract all steps from a scenario outline
     */
    private fun extractOutlineSteps(outline: GherkinScenarioOutline): List<StepInfo> {
        val steps = PsiTreeUtil.findChildrenOfType(outline, GherkinStep::class.java)
        return steps.mapNotNull { step ->
            val stepText = step.text?.trim() ?: return@mapNotNull null
            val callReads = extractCallReads(stepText, step)

            StepInfo(
                text = stepText,
                psiElement = step,
                callReads = callReads
            )
        }
    }

    /**
     * Extract call read statements from a step
     */
    private fun extractCallReads(stepText: String, stepElement: PsiElement): List<CallReadInfo> {
        val callReads = mutableListOf<CallReadInfo>()
        val matcher = CALL_READ_PATTERN.matcher(stepText)

        while (matcher.find()) {
            val featurePath = matcher.group(1)
            val scenarioTag = matcher.group(2)

            callReads.add(
                CallReadInfo(
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
     * Resolve a call read to its target file and scenario(s)
     */
    fun resolveCallRead(callRead: CallReadInfo): ResolvedCallRead? {
        val targetFile = findFeatureFile(callRead.featurePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(targetFile) as? GherkinFile
            ?: return null

        val scenarios = parseFeatureFile(psiFile)

        // If a specific scenario tag is requested, find all scenarios with that tag
        val targetScenarios = if (callRead.scenarioTag != null) {
            scenarios.filter { it.tag == callRead.scenarioTag }
        } else {
            // If no tag specified, return all scenarios
            scenarios
        }

        return ResolvedCallRead(
            psiFile = psiFile,
            scenarios = targetScenarios,
            allScenarios = scenarios
        )
    }

    /**
     * Find a feature file by its path
     */
    private fun findFeatureFile(featurePath: String): VirtualFile? {
        // Try to find the file using various strategies
        
        // Strategy 1: Look in all source roots
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val modules = moduleManager.modules
        
        for (module in modules) {
            val sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots(true)
            for (root in sourceRoots) {
                val file = VfsUtilCore.findRelativeFile(featurePath, root)
                if (file != null && file.exists()) {
                    return file
                }
            }
        }

        // Strategy 2: Search by filename in project
        val fileName = featurePath.substringAfterLast('/')
        val projectDir = project.basePath?.let { 
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
        }
        if (projectDir != null) {
            return searchFileRecursively(projectDir, fileName)
        }

        return null
    }

    /**
     * Recursively search for a file by name
     */
    private fun searchFileRecursively(directory: VirtualFile, fileName: String): VirtualFile? {
        if (!directory.isDirectory) return null

        for (child in directory.children) {
            if (child.isDirectory) {
                val found = searchFileRecursively(child, fileName)
                if (found != null) return found
            } else if (child.name == fileName) {
                return child
            }
        }

        return null
    }

    /**
     * Data classes for parsed information
     */
    data class ScenarioInfo(
        val name: String,
        val tag: String?,
        val psiElement: PsiElement,
        val steps: List<StepInfo>,
        val isOutline: Boolean = false,
        val isBackground: Boolean = false
    )

    data class StepInfo(
        val text: String,
        val psiElement: PsiElement,
        val callReads: List<CallReadInfo>
    )

    data class CallReadInfo(
        val featurePath: String,
        val scenarioTag: String?,
        val psiElement: PsiElement,
        val fullCallText: String
    )

    data class ResolvedCallRead(
        val psiFile: GherkinFile,
        val scenarios: List<ScenarioInfo>,
        val allScenarios: List<ScenarioInfo>
    )
}
