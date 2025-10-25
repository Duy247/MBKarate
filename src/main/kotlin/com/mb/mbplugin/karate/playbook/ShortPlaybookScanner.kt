package com.mb.mbplugin.karate.playbook

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.mb.mbplugin.karate.documentation.KarateFeatureDocumentationProvider
import com.mb.mbplugin.karate.explorer.KarateTreeParser
import com.mb.mbplugin.karate.index.KarateFeatureIndex
import com.mb.mbplugin.karate.psi.KarateFile
import org.jetbrains.plugins.cucumber.psi.GherkinFile

/**
 * Scanner for finding all @short descriptions in feature files
 */
class ShortPlaybookScanner(private val project: Project) {

    private val docProvider = KarateFeatureDocumentationProvider()
    private val parser = KarateTreeParser(project)

    /**
     * Scan all feature files for @short descriptions
     * Optimized for performance by processing in batches
     */
    fun scanForShortDescriptions(): List<ShortPlaybookEntry> {
        val entries = mutableListOf<ShortPlaybookEntry>()
        
        // Get all feature files from the index (must be in read action)
        val featureFiles = KarateFeatureIndex.getAllFeatureFiles(project)
        val psiManager = PsiManager.getInstance(project)
        
        // Process files in batches for better performance
        val batchSize = 50
        featureFiles.chunked(batchSize).forEach { batch ->
            batch.forEach { virtualFile ->
                try {
                    val psiFile = psiManager.findFile(virtualFile) as? KarateFile ?: return@forEach
                    
                    // Extract all documentation from the file
                    val fileEntries = extractEntriesFromFile(psiFile, virtualFile.path)
                    entries.addAll(fileEntries)
                } catch (e: Exception) {
                    // Ignore files that can't be parsed
                }
            }
        }
        
        return entries
    }

    /**
     * Extract @short entries from a single file
     */
    private fun extractEntriesFromFile(karateFile: KarateFile, filePath: String): List<ShortPlaybookEntry> {
        val entries = mutableListOf<ShortPlaybookEntry>()
        
        // Parse all scenarios from the file
        val scenarios = parser.parseFeatureFile(karateFile)
        
        // For each scenario with a tag, check if it has @short documentation
        for (scenario in scenarios) {
            val tag = scenario.tag ?: continue
            
            // Try to extract documentation for this scenario
            val docText = docProvider.extractDocumentationFromFile(karateFile, tag)
            
            if (docText != null) {
                // Parse the documentation to extract @short
                val parsed = docProvider.parseDocDirectives(docText)
                
                if (parsed.shortDescription != null) {
                    entries.add(
                        ShortPlaybookEntry(
                            shortDescription = parsed.shortDescription,
                            scenarioTag = tag,
                            filePath = filePath,
                            scenarioName = scenario.name
                        )
                    )
                }
            }
        }
        
        return entries
    }
}
