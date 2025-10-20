package com.mb.mbplugin.karate.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.mb.mbplugin.karate.index.KarateFeatureIndex
import com.mb.mbplugin.karate.psi.KarateFile
import com.mb.mbplugin.karate.psi.KaratePsiElement
import com.mb.mbplugin.karate.psi.KarateTokenTypes
import java.util.regex.Pattern

class KarateFeatureDocumentationProvider : AbstractDocumentationProvider() {
    
    companion object {
        // Pattern to match: call read('classpath:path/to/file.feature@scenario') or call read("classpath:path/to/file.feature@scenario")
        public val CALL_READ_PATTERN = Pattern.compile(
            "call\\s+read\\s*\\(\\s*['\"]classpath:([^'\"]+\\.feature)(@\\w+)?['\"]",
            Pattern.CASE_INSENSITIVE
        )
        
        // Pattern to match documentation blocks like: * text scenarioName_DOC = """..."""
        public val DOC_PATTERN = Pattern.compile(
            "\\*\\s+text\\s+(\\w+_DOC)\\s*=\\s*\"\"\"([\\s\\S]*?)\"\"\"",
            Pattern.MULTILINE or Pattern.CASE_INSENSITIVE
        )
        
        // Alternative pattern for single line documentation
        public val SINGLE_LINE_DOC_PATTERN = Pattern.compile(
            "\\*\\s+text\\s+(\\w+_DOC)\\s*=\\s*['\"]([^'\"]*)['\"]",
            Pattern.CASE_INSENSITIVE
        )
    }

    public data class DocParseResult(
        val body: String,
        val params: List<String>,
        val paramInputs: List<String>,
        val returns: List<String>
    )

    public fun parseDocDirectives(documentation: String): DocParseResult {
        val paramRegex = Regex("^\\s*@param\\s+(\\S+)", RegexOption.MULTILINE)
        val paramInputRegex = Regex("^\\s*@param_input\\s+(\\S+)", RegexOption.MULTILINE)
        val returnRegex = Regex("^\\s*@return\\s+(\\S+)", RegexOption.MULTILINE)

        val params = paramRegex.findAll(documentation).map { it.groupValues[1] }.toList()
        val paramInputs = paramInputRegex.findAll(documentation).map { it.groupValues[1] }.toList()
        val returns = returnRegex.findAll(documentation).map { it.groupValues[1] }.toList()

        // Remove directive lines from body
        var body = documentation
            .replace(paramRegex, "")
            .replace(paramInputRegex, "")
            .replace(returnRegex, "")
            .trim()

        return DocParseResult(body, params, paramInputs, returns)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null || originalElement == null) return null
        
        // Find the exact call read pattern that contains the cursor position
        val callReadInfo = findCallReadAtPosition(element, originalElement)
        if (callReadInfo == null) return null
        
        val (featureFilePath, scenarioTag) = callReadInfo
        val documentation = findDocumentationForFeature(element.project, featureFilePath, scenarioTag)
        
        // Return formatted documentation or fallback message
        return if (documentation != null) {
            // Documentation found, format it properly
            formatDocumentation(documentation, featureFilePath.substringAfterLast("/"), scenarioTag)
        } else {
            // No documentation found, show appropriate message
            val fileName = featureFilePath.substringAfterLast("/")
            buildString {
                append("<html><body>")
                if (scenarioTag != null) {
                    append("<br><i>No documentation found for scenario '@$scenarioTag'.</i><br>")
                    append("<i>Expected documentation variable: <code>${scenarioTag}_DOC</code></i>")
                } else {
                    append("<br><i>No documentation found for this feature.</i>")
                }
                append("</body></html>")
            }
        }
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null || originalElement == null) return null
        
        // Find the exact call read pattern that contains the cursor position
        val callReadInfo = findCallReadAtPosition(element, originalElement)
        if (callReadInfo == null) return null
        
        val (featureFilePath, scenarioTag) = callReadInfo
        val fileName = featureFilePath.substringAfterLast("/")
        
        return if (scenarioTag != null) {
            "Feature: $fileName, Scenario: $scenarioTag"
        } else {
            "Feature: $fileName"
        }
    }

    override fun getCustomDocumentationElement(
        editor: com.intellij.openapi.editor.Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int
    ): PsiElement? {
        // This helps identify the correct element for documentation
        if (contextElement?.text?.contains("call read") == true) {
            return contextElement
        }
        
        // Check parent elements
        var current = contextElement
        while (current != null) {
            if (current.text.contains("call read")) {
                return current
            }
            current = current.parent
        }
        
        return contextElement
    }

    public fun findCallReadAtPosition(element: PsiElement, originalElement: PsiElement): Pair<String, String?>? {
        // Get the cursor offset within the file
        val offset = originalElement.textOffset
        
        // Start with the current element and traverse up to find a containing element with call read
        var current: PsiElement? = element
        while (current != null) {
            val text = current.text
            val matcher = CALL_READ_PATTERN.matcher(text)
            
            // Find all call read patterns in this element
            while (matcher.find()) {
                val matchStart = current.textOffset + matcher.start()
                val matchEnd = current.textOffset + matcher.end()
                
                // Check if the cursor is within this specific call read statement
                if (offset >= matchStart && offset <= matchEnd) {
                    val featureFilePath = matcher.group(1)
                    val scenarioTag = matcher.group(2)?.substring(1) // Remove @ prefix
                    return Pair(featureFilePath, scenarioTag)
                }
            }
            current = current.parent
        }
        
        return null
    }

    public fun findDocumentationForFeature(
        project: Project, 
        featureFilePath: String, 
        scenarioTag: String?
    ): String? {
        // Extract filename from path
        val fileName = featureFilePath.substringAfterLast("/")
        
        // Get all feature files and filter by name
        val allFeatureFiles = KarateFeatureIndex.getAllFeatureFiles(project)
        val matchingFiles = allFeatureFiles.filter { virtualFile ->
            virtualFile.name == fileName
        }
        
        for (virtualFile in matchingFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile is KarateFile) {
                val documentation = extractDocumentationFromFile(psiFile, scenarioTag)
                if (documentation != null) {
                    // Return raw documentation text, not formatted HTML
                    return documentation
                }
            }
        }
        
        return null
    }

    public fun extractDocumentationFromFile(karateFile: KarateFile, scenarioTag: String?): String? {
        val fileText = karateFile.text

        // Match blocks like:
        // """
        // @logout_DOC
        //   Example:
        //       * call read
        // """
        val INLINE_DOC_PATTERN = Pattern.compile(
            "\"\"\"\\s*@([A-Za-z0-9_]+_DOC)\\s*\\n([\\s\\S]*?)\"\"\"",
            Pattern.MULTILINE
        )

        if (scenarioTag != null) {
            val expectedDocVarName = "${scenarioTag}_DOC"
            val matcher = INLINE_DOC_PATTERN.matcher(fileText)

            while (matcher.find()) {
                val docVarName = matcher.group(1)
                val docContent = matcher.group(2)
                if (docVarName == expectedDocVarName) {
                    return docContent.trim()
                }
            }

            return null
        }

        // If no specific tag provided, return the first found doc block
        val matcher = INLINE_DOC_PATTERN.matcher(fileText)
        if (matcher.find()) {
            return matcher.group(2).trim()
        }

        return null
    }


    public fun formatDocumentation(documentation: String, fileName: String, scenarioTag: String?): String {
        val safeDocumentation = documentation.takeIf { it.isNotBlank() } ?: "No documentation content available"
        val safeFileName = fileName.takeIf { it.isNotBlank() } ?: "Unknown file"

        val parsed = parseDocDirectives(safeDocumentation)
        val processedBody = removeLeadingIndentation(parsed.body)

        return buildString {
            append("<html><body style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;\">")
            append("<pre style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; margin: 0; padding: 0;\">")
            append(escapeHtml(processedBody))
            append("</pre>")

            // Required predefined variables
            if (parsed.params.isNotEmpty()) {
                append("<br><b>Required Variables:</b><ul>")
                parsed.params.forEach { param ->
                    append("<li><code>${escapeHtml(param)}</code> — must be predefined before calling</li>")
                }
                append("</ul>")
            }

            // Inputs passed via call read
            if (parsed.paramInputs.isNotEmpty()) {
                append("<br><b>Direct Inputs:</b><ul>")
                parsed.paramInputs.forEach { param ->
                    append("<li><code>${escapeHtml(param)}</code> — must be passed when calling <code>call read()</code></li>")
                }
                append("</ul>")
            }

            // Return values
            if (parsed.returns.isNotEmpty()) {
                append("<br><b>Returns:</b><ul>")
                parsed.returns.forEach { ret ->
                    append("<li><code>${escapeHtml(ret)}</code> — output variable from this scenario</li>")
                }
                append("</ul>")
            }

            append("</body></html>")
        }
    }

    
    /**
     * Removes leading indentation from documentation text.
     * This ensures all lines are aligned to the left regardless of original indentation.
     */
    public fun removeLeadingIndentation(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) return text
        
        return lines.joinToString("\n") { line ->
            line.trimStart()
        }
    }

    public fun escapeHtml(text: String): String {
        // Escape all HTML first
        var escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        // Then unescape safe tags: <b>, <i>, <br>
        escaped = escaped
            .replace("&lt;b&gt;", "<b>")
            .replace("&lt;/b&gt;", "</b>")
            .replace("&lt;i&gt;", "<i>")
            .replace("&lt;/i&gt;", "</i>")
            .replace("&lt;br&gt;", "<br>")
            .replace("&lt;br/&gt;", "<br/>")

        return escaped
    }

    fun extractParamInputsFromDoc(docText: String): List<String> {
        val regex = Regex("@param_input\\s+([A-Za-z_][A-Za-z0-9_]*)")
        return regex.findAll(docText).map { it.groupValues[1] }.toList()
    }
}