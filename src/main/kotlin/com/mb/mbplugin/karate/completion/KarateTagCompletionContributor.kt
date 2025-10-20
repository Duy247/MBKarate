package com.mb.mbplugin.karate.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.plugins.cucumber.psi.GherkinLanguage
import com.mb.mbplugin.karate.index.KarateFeatureIndex

/**
 * Provides autocomplete for Karate tags with call read('classpath:...') suggestions
 * Uses ! symbol for tag-based completion and # symbol for file-based completion
 */
class KarateTagCompletionContributor : CompletionContributor() {
    
    init {
        // Trigger completion more specifically - only in Gherkin files
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(GherkinLanguage.INSTANCE),
            KarateTagCompletionProvider()
        )
    }
}

class KarateTagCompletionProvider : CompletionProvider<CompletionParameters>() {
    
    companion object {
        private val LOG = Logger.getInstance(KarateTagCompletionProvider::class.java)
    }
    
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val element = parameters.position
        val offset = parameters.offset
        val file = parameters.originalFile
        val project = file.project
        
        // Get the text before cursor
        val textBeforeCursor = file.text.substring(0, offset)
        val lastExclamationIndex = textBeforeCursor.lastIndexOf('!')
        val lastHashIndex = textBeforeCursor.lastIndexOf('#')
        
        // Check if we're in a context where completion should be triggered
        // 1. After ! symbol for tag-based completion (only in specific contexts)
        // 2. After # symbol for feature file name completion (only in specific contexts)
        
        var partialTag = ""
        var isTagCompletion = false
        var isFileCompletion = false
        
        // Check which trigger is more recent and within a stricter range
        val maxTriggerDistance = 20 // Reduced from 50 to be more restrictive for tags
        val maxFileCompletionDistance = 30 // Slightly more permissive for file names
        
        if (lastExclamationIndex != -1 && offset - lastExclamationIndex <= maxTriggerDistance && 
            (lastHashIndex == -1 || lastExclamationIndex > lastHashIndex)) {
            
            // Only trigger ! completion in valid contexts
            if (isValidTagCompletionContext(textBeforeCursor, lastExclamationIndex)) {
                partialTag = textBeforeCursor.substring(lastExclamationIndex + 1)
                    .takeWhile { it.isLetterOrDigit() || it == '_' || Character.isLetter(it) }
                isTagCompletion = true
            }
        } else if (lastHashIndex != -1 && offset - lastHashIndex <= maxFileCompletionDistance && 
                   (lastExclamationIndex == -1 || lastHashIndex > lastExclamationIndex)) {
            
            // Only trigger # completion in valid contexts
            if (isValidFileCompletionContext(textBeforeCursor, lastHashIndex)) {
                partialTag = textBeforeCursor.substring(lastHashIndex + 1)
                    .takeWhile { it.isLetterOrDigit() || it == '_' || Character.isLetter(it) || it == '.' }
                isFileCompletion = true
            }
        }
        
        // If neither completion type is triggered, return early
        if (!isTagCompletion && !isFileCompletion) {
            return
        }
        
        // Additional safety check: ensure we're not in the middle of a regular word
        // by checking if there's a word character immediately before our trigger symbol
        if (isTagCompletion && lastExclamationIndex > 0) {
            val charBeforeExclamation = textBeforeCursor[lastExclamationIndex - 1]
            if (charBeforeExclamation.isLetterOrDigit() || charBeforeExclamation == '_') {
                // We're in the middle of a word, skip completion
                return
            }
        }
        
        if (isFileCompletion && lastHashIndex > 0) {
            val charBeforeHash = textBeforeCursor[lastHashIndex - 1]
            // Only allow hash completion after whitespace, quotes, or path separators
            if (charBeforeHash.isLetterOrDigit() || charBeforeHash == '_') {
                // We're in the middle of a word or identifier, skip completion
                return
            }
        }
        
        // Get all matching tags and feature files
        val matchingItems = getAllMatchingItems(project, partialTag, isTagCompletion, isFileCompletion)
        
        // Create completion suggestions
        matchingItems.forEach { tagData ->
            // Properly normalize the path for classpath usage - handles UTF-8 and spaces
            val normalizedPath = normalizeClasspathPath(tagData.relativePath)
            
            if (tagData.tag.isEmpty()) {
                // This is a feature file suggestion (tag is empty)
                val suggestion = "* call read('classpath:${normalizedPath}')"
                val fileName = java.io.File(tagData.absolutePath).nameWithoutExtension
                
                // Create lookup string that shows the file name
                val uniqueLookupString = fileName
                val presentableText = "* call read('classpath:${normalizedPath}')"
                
                val lookupElement = LookupElementBuilder.create(uniqueLookupString)
                    .withPresentableText(presentableText)
                    .withTypeText("${fileName}.feature")
                    .withTailText(" (${normalizedPath})", true)
                    .withInsertHandler { insertContext, _ ->
                        handleFeatureFileInsertion(insertContext, suggestion, isTagCompletion, isFileCompletion)
                    }
                
                result.addElement(lookupElement)
            } else {
                // This is a tag-based suggestion
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(
                    com.intellij.openapi.vfs.VfsUtil.findFileByIoFile(java.io.File(tagData.absolutePath), true)!!
                )

                var paramInputs = emptyList<String>()
                if (psiFile is com.mb.mbplugin.karate.psi.KarateFile) {
                    val docText = com.mb.mbplugin.karate.documentation.KarateFeatureDocumentationProvider()
                        .extractDocumentationFromFile(psiFile, tagData.tag)
                    if (!docText.isNullOrBlank()) {
                        paramInputs = com.mb.mbplugin.karate.documentation.KarateFeatureDocumentationProvider()
                            .extractParamInputsFromDoc(docText)
                    }
                }

                val paramBlock = if (paramInputs.isNotEmpty()) {
                    paramInputs.joinToString(", ", prefix = " { ", postfix = " }") { "$it: ''" }
                } else {
                    ""
                }

                val suggestion = "* call read('classpath:${normalizedPath}@${tagData.tag}')$paramBlock"
                                
                // Create a completely unique lookup string by including file path
                val fileName = java.io.File(tagData.absolutePath).nameWithoutExtension
                val uniqueLookupString = "!${tagData.tag}__${fileName}__${System.nanoTime()}"
                
                // Make the presentable text show the actual suggestion
                val presentableText = "* call read('classpath:${normalizedPath}@${tagData.tag}')"
                
                val lookupElement = LookupElementBuilder.create(uniqueLookupString)
                    .withPresentableText(presentableText)
                    .withLookupString("!${tagData.tag}")  // This is what the user types to match
                    .withTypeText("${fileName}.feature")
                    .withTailText(" (${normalizedPath})", true)
                    .withInsertHandler { insertContext, _ ->
                        // Use the appropriate handler based on which trigger was used
                        if (isFileCompletion) {
                            // If triggered by #, use feature file insertion handler
                            handleFeatureFileInsertion(insertContext, suggestion, isTagCompletion, isFileCompletion)
                        } else {
                            // If triggered by !, use tag insertion handler
                            handleTagInsertion(insertContext, suggestion)
                        }
                    }
                
                result.addElement(lookupElement)
            }
        }
    }
    
    private fun getAllMatchingItems(
        project: com.intellij.openapi.project.Project, 
        prefix: String,
        isTagCompletion: Boolean,
        isFileCompletion: Boolean
    ): List<com.mb.mbplugin.karate.index.KarateFeatureData> {
        val result = mutableListOf<com.mb.mbplugin.karate.index.KarateFeatureData>()
        
        try {
            // Get all feature files
            val allFeatureFiles = KarateFeatureIndex.getAllFeatureFiles(project)
            
            allFeatureFiles.forEach { virtualFile ->
                try {
                    val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile != null) {
                        val relativePath = getRelativePathFromSourceRoot(project, virtualFile)
                        
                        // Debug logging for path issues
                        LOG.debug("Processing file: ${virtualFile.path}")
                        LOG.debug("Relative path: $relativePath")
                        
                        if (isTagCompletion) {
                            // Tag-based completion - find tags that contain the prefix
                            val tags = extractTagsFromFile(psiFile.text)
                            LOG.debug("Found tags: $tags")
                            
                            // Filter tags that contain the prefix anywhere in the tag name (case insensitive)
                            tags.filter { tag -> 
                                prefix.isEmpty() || tag.contains(prefix, ignoreCase = true)
                            }.forEach { tag ->
                                result.add(
                                    com.mb.mbplugin.karate.index.KarateFeatureData(
                                        relativePath, tag, virtualFile.path
                                    )
                                )
                            }
                        }
                        
                        if (isFileCompletion) {
                            // Feature file name-based suggestions (only when # is used)
                            val fileName = virtualFile.nameWithoutExtension
                            if (prefix.isEmpty() || fileName.contains(prefix, ignoreCase = true)) {
                                // 1. Create entry for the feature file itself
                                result.add(
                                    com.mb.mbplugin.karate.index.KarateFeatureData(
                                        relativePath, "", virtualFile.path
                                    )
                                )
                                
                                // 2. Create entries for ALL tags from this matching file
                                val tags = extractTagsFromFile(psiFile.text)
                                tags.forEach { tag ->
                                    result.add(
                                        com.mb.mbplugin.karate.index.KarateFeatureData(
                                            relativePath, tag, virtualFile.path
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore individual file errors
                }
            }
        } catch (e: Exception) {
            // Ignore errors
        }
        
        // Return unique entries - only remove duplicates where the same tag appears multiple times in the same file
        // But allow the same tag to appear from different files
        return result.distinctBy { "${it.absolutePath}@${it.tag}" }
    }

    private fun handleTagInsertion(insertContext: com.intellij.codeInsight.completion.InsertionContext, suggestion: String) {
        val document = insertContext.document
        val startOffset = insertContext.startOffset
        val tailOffset = insertContext.tailOffset
        
        // Remove what was just inserted by the completion system
        document.deleteString(startOffset, tailOffset)
        
        // Find and replace the !tag pattern
        val currentText = document.text
        var replaceStart = startOffset - 1
        
        // Go backwards to find the ! symbol
        while (replaceStart >= 0 && currentText[replaceStart] != '!') {
            val char = currentText[replaceStart]
            if (!char.isLetterOrDigit() && char != '_' && !Character.isLetter(char)) {
                break
            }
            replaceStart--
        }
        
        // If we found !, replace from there
        if (replaceStart >= 0 && currentText[replaceStart] == '!') {
            var replaceEnd = replaceStart + 1
            // Find end of tag
            while (replaceEnd < currentText.length && 
                   (currentText[replaceEnd].isLetterOrDigit() || 
                    currentText[replaceEnd] == '_' || 
                    Character.isLetter(currentText[replaceEnd]))) {
                replaceEnd++
            }
            
            // Replace !tag with our suggestion
            document.replaceString(replaceStart, replaceEnd, suggestion)
        } else {
            // Fallback: just insert at current position
            document.insertString(startOffset, suggestion)
        }
    }

    private fun handleFeatureFileInsertion(
        insertContext: com.intellij.codeInsight.completion.InsertionContext, 
        suggestion: String,
        isTagCompletion: Boolean,
        isFileCompletion: Boolean
    ) {
        val document = insertContext.document
        val startOffset = insertContext.startOffset
        val tailOffset = insertContext.tailOffset
        
        // Remove what was just inserted by the completion system
        document.deleteString(startOffset, tailOffset)
        
        if (isTagCompletion) {
            // Handle insertion like tag completion (after !)
            handleTagInsertion(insertContext, suggestion)
        } else if (isFileCompletion) {
            // Handle insertion for feature file name completion (after #) - copy @ logic
            val currentText = document.text
            var replaceStart = startOffset - 1
            
            // Go backwards to find the # symbol
            while (replaceStart >= 0 && currentText[replaceStart] != '#') {
                val char = currentText[replaceStart]
                if (!char.isLetterOrDigit() && char != '_' && !Character.isLetter(char)) {
                    break
                }
                replaceStart--
            }
            
            // If we found #, replace from there
            if (replaceStart >= 0 && currentText[replaceStart] == '#') {
                var replaceEnd = replaceStart + 1
                // Find end of tag
                while (replaceEnd < currentText.length && 
                       (currentText[replaceEnd].isLetterOrDigit() || 
                        currentText[replaceEnd] == '_' || 
                        Character.isLetter(currentText[replaceEnd]))) {
                    replaceEnd++
                }
                
                // Replace #word with our suggestion
                document.replaceString(replaceStart, replaceEnd, suggestion)
            } else {
                // Fallback: just insert at current position
                document.insertString(startOffset, suggestion)
            }
        }
    }

    private fun getAllTagsStartingWith(
        project: com.intellij.openapi.project.Project, 
        prefix: String
    ): List<com.mb.mbplugin.karate.index.KarateFeatureData> {
        // This method is kept for backward compatibility but now delegates to getAllMatchingItems
        return getAllMatchingItems(project, prefix, true, false)
    }
    
    private fun extractTagsFromFile(text: String): List<String> {
        val tags = mutableListOf<String>()
        
        // Enhanced regex to find tags like @login, @smoke, etc. 
        // Supports UTF-8 characters in tag names using Unicode property classes:
        // \p{L} = any Unicode letter (supports accented characters, Asian characters, etc.)
        // \p{N} = any Unicode number
        val tagPattern = Regex("(?:^|\\s)@([\\p{L}_][\\p{L}\\p{N}_]*)", RegexOption.MULTILINE)
        tagPattern.findAll(text).forEach { match ->
            tags.add(match.groupValues[1]) // Add without @ symbol
        }
        
        return tags.distinct()
    }
    
    private fun getRelativePathFromSourceRoot(
        project: com.intellij.openapi.project.Project, 
        file: com.intellij.openapi.vfs.VirtualFile
    ): String {
        val projectPath = project.basePath ?: return file.path
        val filePath = file.path
        
        // Common source root patterns for Karate tests
        val sourceRoots = listOf(
            "src/test/java", 
            "src/main/java", 
            "src/test/resources", 
            "src/main/resources",
            "src/test/kotlin",
            "src/main/kotlin"
        )
        
        for (sourceRoot in sourceRoots) {
            val sourceRootPath = "$projectPath/$sourceRoot".replace("/", java.io.File.separator)
            if (filePath.startsWith(sourceRootPath)) {
                // Return path after the source root (this is the classpath)
                val relativePath = filePath.substring(sourceRootPath.length + 1)
                    .replace("\\", "/")
                    .replace(java.io.File.separator, "/")
                // Ensure proper UTF-8 encoding and handle spaces
                return relativePath
            }
        }
        
        // Fallback: try to find using module source roots from IntelliJ
        try {
            val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
            val modules = moduleManager.modules
            for (module in modules) {
                val moduleRootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
                
                // Check all source roots
                val sourceRoots = moduleRootManager.sourceRoots
                for (sourceRoot in sourceRoots) {
                    if (filePath.startsWith(sourceRoot.path)) {
                        val relativePath = filePath.substring(sourceRoot.path.length + 1)
                            .replace("\\", "/")
                            .replace(java.io.File.separator, "/")
                        // Ensure proper UTF-8 encoding and handle spaces
                        return relativePath
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore module resolution errors
        }
        
        // Final fallback: relative to project root
        return if (filePath.startsWith(projectPath)) {
            val relativePath = filePath.substring(projectPath.length + 1)
                .replace("\\", "/")
                .replace(java.io.File.separator, "/")
            // Ensure proper UTF-8 encoding and handle spaces
            relativePath
        } else {
            file.name
        }
    }
    
    /**
     * Normalize path for classpath usage - handles UTF-8 and spaces properly
     * Karate's classpath supports spaces and UTF-8 characters natively,
     * so no URL encoding is needed, just consistent forward slashes.
     */
    private fun normalizeClasspathPath(path: String): String {
        // Classpath in Karate supports spaces and UTF-8 characters natively
        // Just ensure forward slashes for consistency
        return path.replace("\\", "/")
    }
    
    /**
     * Check if ! completion should be triggered in the current context.
     * Tag completion should only be triggered in specific scenarios:
     * 1. At the beginning of a line (tag definition)
     * 2. After 'call read(' patterns for scenario references
     * 3. In specific test runner contexts
     */
    private fun isValidTagCompletionContext(textBeforeCursor: String, exclamationIndex: Int): Boolean {
        // Get the text on the current line before the !
        val textUpToExclamation = textBeforeCursor.substring(0, exclamationIndex)
        val lastNewlineIndex = textUpToExclamation.lastIndexOf('\n')
        val currentLinePrefix = if (lastNewlineIndex == -1) {
            textUpToExclamation
        } else {
            textUpToExclamation.substring(lastNewlineIndex + 1)
        }.trim()
        
        LOG.debug("Tag completion context check: line prefix = '$currentLinePrefix'")
        
        // Case 1: ! at the beginning of a line (tag definition)
        // Only allow if it's truly at the start of a line or after whitespace
        if (currentLinePrefix.isEmpty()) {
            LOG.debug("Tag completion allowed: beginning of line")
            return true
        }
        
        // Case 2: After 'call read(' patterns for scenario references
        // Look for patterns like: call read('classpath:file.feature! (but generates @tag in output)
        val callReadWithExclamationPattern = Regex("""call\s+read\s*\(\s*['"]classpath:[^'"]*$""", RegexOption.IGNORE_CASE)
        if (callReadWithExclamationPattern.containsMatchIn(currentLinePrefix)) {
            LOG.debug("Tag completion allowed: call read pattern")
            return true
        }
        
        // Case 3: Within call read with file path already specified
        // Look for: call read('classpath:some/path/file.feature! (but generates @tag in output)
        val filePathWithExclamationPattern = Regex("""call\s+read\s*\(\s*['"]classpath:[^'"]*\.feature$""", RegexOption.IGNORE_CASE)
        if (filePathWithExclamationPattern.containsMatchIn(currentLinePrefix)) {
            LOG.debug("Tag completion allowed: file path with !")
            return true
        }
        
        // Case 4: In Karate tag runner contexts (like .tags("!
        val tagsMethodPattern = Regex("""\.tags\s*\(\s*['"]$""", RegexOption.IGNORE_CASE)
        if (tagsMethodPattern.containsMatchIn(currentLinePrefix)) {
            LOG.debug("Tag completion allowed: tags method")
            return true
        }
        
        // Case 5: Only in specific comment contexts that make sense for tag references
        if (currentLinePrefix.startsWith("#") && 
            (currentLinePrefix.contains("tag", ignoreCase = true) || 
             currentLinePrefix.contains("scenario", ignoreCase = true))) {
            LOG.debug("Tag completion allowed: relevant comment")
            return true
        }
        
        LOG.debug("Tag completion denied: invalid context")
        return false
    }
    
    /**
     * Check if # completion should be triggered in the current context.
     * File completion should be more permissive than tag completion:
     * 1. After 'call read(' patterns for file references
     * 2. In general contexts where # might reference a file
     * 3. NOT in the middle of existing identifiers or inappropriate contexts
     */
    private fun isValidFileCompletionContext(textBeforeCursor: String, hashIndex: Int): Boolean {
        // Get the text on the current line before the #
        val textUpToHash = textBeforeCursor.substring(0, hashIndex)
        val lastNewlineIndex = textUpToHash.lastIndexOf('\n')
        val currentLinePrefix = if (lastNewlineIndex == -1) {
            textUpToHash
        } else {
            textUpToHash.substring(lastNewlineIndex + 1)
        }.trim()
        
        LOG.debug("File completion context check: line prefix = '$currentLinePrefix'")
        
        // Case 1: After 'call read(' patterns for file references
        // Look for patterns like: call read('classpath:#
        val callReadPattern = Regex("""call\s+read\s*\(\s*['"]classpath:\s*$""", RegexOption.IGNORE_CASE)
        if (callReadPattern.containsMatchIn(currentLinePrefix)) {
            LOG.debug("File completion allowed: call read pattern")
            return true
        }
        
        // Case 2: In the middle of a classpath specification
        // Look for: call read('classpath:some/path/#
        val classpathMidPattern = Regex("""call\s+read\s*\(\s*['"]classpath:[^'"]*/$""", RegexOption.IGNORE_CASE)
        if (classpathMidPattern.containsMatchIn(currentLinePrefix)) {
            LOG.debug("File completion allowed: classpath path")
            return true
        }
        
        // Case 3: In comment contexts (more permissive for file references)
        if (currentLinePrefix.startsWith("#")) {
            LOG.debug("File completion allowed: comment context")
            return true
        }
        
        // Case 4: After whitespace or at reasonable boundaries (more permissive)
        // Don't allow if it's clearly in the middle of code that's not file-related
        if (hashIndex > 0) {
            val charBeforeHash = textBeforeCursor[hashIndex - 1]
            
            // Deny if it's clearly in inappropriate contexts
            if (currentLinePrefix.contains("=") && !currentLinePrefix.contains("read") && !currentLinePrefix.contains("call")) {
                // Looks like a variable assignment, not file reference
                LOG.debug("File completion denied: variable assignment context")
                return false
            }
            
            if (currentLinePrefix.matches(Regex(".*\\b(def|function|class|method)\\b.*", RegexOption.IGNORE_CASE))) {
                // Inside function/class definitions
                LOG.debug("File completion denied: function/class definition")
                return false
            }
            
            // If it's after reasonable separators, allow it
            if (charBeforeHash.isWhitespace() || charBeforeHash == ':' || charBeforeHash == '/' || 
                charBeforeHash == '(' || charBeforeHash == '\'' || charBeforeHash == '"') {
                LOG.debug("File completion allowed: after reasonable separator")
                return true
            }
        }
        
        // Case 5: Beginning of line (more permissive)
        if (currentLinePrefix.isEmpty()) {
            LOG.debug("File completion allowed: beginning of line")
            return true
        }
        
        // Case 6: In contexts that might reference files
        val fileReferenceKeywords = listOf("read", "load", "include", "import", "file", "feature", "call")
        if (fileReferenceKeywords.any { keyword -> 
            currentLinePrefix.contains(keyword, ignoreCase = true) 
        }) {
            LOG.debug("File completion allowed: file reference keywords")
            return true
        }
        
        LOG.debug("File completion denied: no valid context found")
        return false
    }
}