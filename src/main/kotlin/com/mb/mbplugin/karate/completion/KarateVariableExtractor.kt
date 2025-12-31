package com.mb.mbplugin.karate.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.mb.mbplugin.settings.KarateVariableSuggestionSettings
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinScenario
import org.jetbrains.plugins.cucumber.psi.GherkinScenarioOutline
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import java.util.Locale
import java.util.regex.Pattern

/**
 * Service for extracting variable definitions from Karate feature files
 * Scans all feature files for def statements regardless of relationships
 */
@Service(Service.Level.PROJECT)
class KarateVariableExtractor(private val project: Project) {

    private data class CachedFile(
        val stamp: Long,
        val variables: Set<VariableInfo>
    )

    private val cacheByPath = mutableMapOf<String, CachedFile>()
    private val cacheBySourcePath = mutableMapOf<String, CachedFile>()
    
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
     * This includes variables from all feature files, regardless of relationships
     */
    @Suppress("UNUSED_PARAMETER")
    fun extractVariablesForScenario(
        scenario: PsiElement,
        currentStepOffset: Int
    ): Set<VariableInfo> {
        val variables = mutableSetOf<VariableInfo>()

        val settings = KarateVariableSuggestionSettings.getInstance(project)
        if (!settings.isEnabled) return emptySet()

        val allFeatureFiles = com.intellij.psi.search.FileTypeIndex.getFiles(
            org.jetbrains.plugins.cucumber.psi.GherkinFileType.INSTANCE,
            com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        )

        val seenPaths = mutableSetOf<String>()
        for (vFile in allFeatureFiles) {
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile) as? GherkinFile ?: continue
            val filePath = vFile.path
            seenPaths.add(filePath)

            val fileStamp = psiFile.modificationStamp
            val cached = cacheByPath[filePath]
            if (cached == null || cached.stamp != fileStamp) {
                val extracted = extractVariablesFromFile(psiFile)
                cacheByPath[filePath] = CachedFile(fileStamp, extracted)
            }
        }

        if (cacheByPath.isNotEmpty()) {
            val removed = cacheByPath.keys - seenPaths
            for (path in removed) {
                cacheByPath.remove(path)
            }
        }

        for (cached in cacheByPath.values) {
            variables.addAll(cached.variables)
        }

        val sourcePaths = settings.sourceFiles.toList()
        val seenSourcePaths = mutableSetOf<String>()
        for (path in sourcePaths) {
            val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: continue
            seenSourcePaths.add(path)
            val fileStamp = vFile.modificationStamp
            val cached = cacheBySourcePath[path]
            if (cached == null || cached.stamp != fileStamp) {
                val extracted = extractVariablesFromSourceFile(vFile)
                cacheBySourcePath[path] = CachedFile(fileStamp, extracted)
            }
        }

        if (cacheBySourcePath.isNotEmpty()) {
            val removed = cacheBySourcePath.keys - seenSourcePaths
            for (path in removed) {
                cacheBySourcePath.remove(path)
            }
        }

        for (cached in cacheBySourcePath.values) {
            variables.addAll(cached.variables)
        }

        return variables
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
     * Collect def variables from a scenario
     */
    private fun collectDefVariablesFromScenario(
        scenario: PsiElement,
        variables: MutableSet<VariableInfo>,
        fileName: String,
        scenarioTag: String?
    ) {
        val steps = when (scenario) {
            is GherkinScenario -> PsiTreeUtil.findChildrenOfType(scenario, GherkinStep::class.java)
            is GherkinScenarioOutline -> PsiTreeUtil.findChildrenOfType(scenario, GherkinStep::class.java)
            else -> return
        }
        
        for (step in steps) {
            val stepText = step.text ?: continue
            
            // Extract def statements from this step
            extractDefStatements(stepText, variables, fileName, scenarioTag)
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

    private fun extractVariablesFromFile(file: GherkinFile): Set<VariableInfo> {
        val variables = mutableSetOf<VariableInfo>()
        val fileName = file.name

        val background = PsiTreeUtil.findChildrenOfType(file, GherkinScenario::class.java)
            .firstOrNull { it.firstChild?.text?.trim()?.lowercase()?.startsWith("background") == true }

        if (background != null) {
            collectDefVariablesFromScenario(background, variables, fileName, "Background")
        }

        val scenarios = PsiTreeUtil.findChildrenOfType(file, GherkinScenario::class.java).toList() +
            PsiTreeUtil.findChildrenOfType(file, GherkinScenarioOutline::class.java).toList()

        for (scenarioElement in scenarios) {
            val keyword = scenarioElement.firstChild?.text?.trim()?.lowercase()
            if (keyword == "background" || keyword == "background:") continue
            collectDefVariablesFromScenario(
                scenarioElement,
                variables,
                fileName,
                extractScenarioTag(scenarioElement)
            )
        }

        return variables
    }

    private fun extractVariablesFromSourceFile(file: com.intellij.openapi.vfs.VirtualFile): Set<VariableInfo> {
        val variables = mutableSetOf<VariableInfo>()
        val fileName = file.name
        val text = try {
            com.intellij.openapi.vfs.VfsUtilCore.loadText(file)
        } catch (e: Exception) {
            return variables
        }

        when (file.extension?.lowercase(Locale.ROOT)) {
            "js" -> extractFromJs(text, variables, fileName)
            "java" -> extractFromJava(text, variables, fileName)
        }

        return variables
    }

    private fun extractFromJs(text: String, variables: MutableSet<VariableInfo>, fileName: String) {
        val patterns = listOf(
            Pattern.compile("\\b(?:var|let|const)\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.MULTILINE),
            Pattern.compile("karate\\.set\\s*\\(\\s*['\"]([a-zA-Z_][a-zA-Z0-9_]*)['\"]\\s*,", Pattern.MULTILINE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val varName = matcher.group(1)
                if (varName.isNotEmpty()) {
                    variables.add(VariableInfo(varName, fileName, null))
                }
            }
        }
    }

    private fun extractFromJava(text: String, variables: MutableSet<VariableInfo>, fileName: String) {
        val javaPattern = Pattern.compile(
            "\\b(?:public|protected|private|static|final|transient|volatile|\\s)*" +
                "(?:byte|short|int|long|float|double|boolean|char|String|[A-Z][A-Za-z0-9_]*)" +
                "(?:\\s*<[^;=(){}]+>)?" +
                "(?:\\s*\\[\\])?\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=|;|,)",
            Pattern.MULTILINE
        )

        val matcher = javaPattern.matcher(text)
        while (matcher.find()) {
            val varName = matcher.group(1)
            if (varName.isNotEmpty()) {
                variables.add(VariableInfo(varName, fileName, null))
            }
        }
    }

    companion object {
        fun getInstance(project: Project): KarateVariableExtractor =
            project.getService(KarateVariableExtractor::class.java)
    }
}
