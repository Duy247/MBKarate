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

    data class DocParseResult(
        val body: String,
        val params: List<ParamEntry>,
        val returns: List<ReturnEntry>,
        val snippetBefore: String?,
        val snippetAfter: String?,
        val shortDescription: String?
    )
    data class ParamEntry(
        val name: String,
        val tags: List<String>,
        val description: String
    )

    data class ReturnEntry(
        val name: String,
        val description: String
    )

    fun parseDocDirectives(documentation: String): DocParseResult {
        val paramRegex = Regex(
            """@param\s+((?:\{[^}]+})*)\s*(\w+)(?:\s*\n(?!\s*@param|\s*@return|\s*@snippet_before|\s*@snippet_after)([^\n]*))?""",
            RegexOption.MULTILINE
        )
        val returnRegex = Regex(
            """@return\s+(\w+)(?:\s*\n(?!\s*@param|\s*@snippet_before|\s*@snippet_after|\s*@return)([^\n]*))?""",
            RegexOption.MULTILINE
        )
        val snippetBeforeRegex = Regex(
            "@snippet_before\\s+([\\s\\S]*?)(?=^\\s*@\\w+|\\Z)",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )
        val snippetAfterRegex = Regex(
            "@snippet_after\\s+([\\s\\S]*?)(?=^\\s*@\\w+|\\Z)",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )
        val shortRegex = Regex(
            "^\\s*@short\\s+([^\\r\\n]+)\\s*(?:\\r?\\n|\\Z)",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )


        val params = paramRegex.findAll(documentation).map {
            val rawTags = it.groupValues[1]
            val tags = Regex("\\{([^}]+)}").findAll(rawTags).map { t -> t.groupValues[1] }.toList()
            val name = it.groupValues[2]
            val desc = it.groupValues[3].trim()
            ParamEntry(name, tags, desc)
        }.toList()

        val returns = returnRegex.findAll(documentation).map {
            val name = it.groupValues[1]
            val desc = it.groups[2]?.value?.trim().orEmpty()
            ReturnEntry(name, desc)
        }.toList()
        val snippetBefore = snippetBeforeRegex.find(documentation)?.groupValues?.get(1)?.trim()
        val snippetAfter = snippetAfterRegex.find(documentation)?.groupValues?.get(1)?.trim()
        val shortDesc = shortRegex.find(documentation)?.groupValues?.get(1)?.trim()

        var body = documentation
            .replace(paramRegex, "")
            .replace(returnRegex, "")
            .replace(snippetBeforeRegex, "")
            .replace(snippetAfterRegex, "")
            .replace(shortRegex, "")
            .trim()

        return DocParseResult(body, params, returns, snippetBefore, snippetAfter, shortDesc)
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
            if (!parsed.shortDescription.isNullOrBlank()) {
                append("<pre style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; background-color: rgba(79, 195, 247, 0.08); font-style: italic; font-size: 1em; color: #b3e5fc;\">")
                append(escapeHtml(parsed.shortDescription!!).trimIndent())
                append("</pre><br>")
            }

            append("<html><body style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;\">")
            append("<pre style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; margin: 0; padding: 0;\">")
            append(escapeHtml(processedBody))
            append("</pre><br>")

            // Required predefined variables
            // === Parameters Table ===
            if (parsed.params.isNotEmpty()) {
                append("<br><b>Parameters:</b>")
                append(
                    """
                    <table style="border-collapse: collapse; width: 500px; margin-top: 4px; white-space: nowrap;">
                        <tr style="background-color: #2b2b2b; color: #ffffff;">
                            <th style="text-align: left; padding: 4px; border: 1px solid #444;">Name</th>
                            <th style="text-align: left; padding: 4px; border: 1px solid #444;">Tags</th>
                            <th style="text-align: left; padding: 4px; border: 1px solid #444;">Description</th>
                        </tr>
                    """.trimIndent()
                )

                parsed.params.forEach { param ->
                    val tagText = if (param.tags.isNotEmpty()) {
                        param.tags.joinToString(" ") { rawTag ->
                            rawTag.split("/").joinToString("/") { tag ->
                                val lower = tag.lowercase()
                                val color = when {
                                    "required" in lower -> "#e53935"   // red
                                    "optional" in lower -> "#1e88e5"   // blue
                                    "input" in lower -> "#43a047"      // green
                                    "predefined" in lower -> "#fb8c00" // orange
                                    "default" in lower -> "#fbf700ff" // orange
                                    else -> "#757575"                  // gray fallback
                                }
                                "<b><font color=\"$color\">${escapeHtml(tag)}</font></b>"
                            }
                        }
                    } else ""

                    val desc = if (param.description.isNotBlank())
                        escapeHtml(param.description)
                    else
                        "&mdash;"

                    append(
                        """
                        <tr>
                            <td style="padding: 4px; border: 1px solid #444;"><code>${escapeHtml(param.name)}</code></td>
                            <td style="padding: 4px; border: 1px solid #444; white-space: wrap;">$tagText</td>
                            <td style="padding: 4px; border: 1px solid #444; white-space: wrap;">$desc</td>
                        </tr>
                        """.trimIndent()
                    )
                }
                append("</table>")
            }

            // === Returns Table ===
            if (parsed.returns.isNotEmpty()) {
                append("<br><b>Returns:</b>")
                append(
                    """
                    <table style="border-collapse: collapse; width: 500px; margin-top: 4px; white-space: nowrap;">
                        <tr style="background-color: #2b2b2b; color: #ffffff;">
                            <th style="text-align: left; padding: 4px; border: 1px solid #444;">Name</th>
                            <th style="text-align: left; padding: 4px; border: 1px solid #444;">Description</th>
                        </tr>
                    """.trimIndent()
                )

                parsed.returns.forEach { ret ->
                    val desc = if (ret.description.isNotBlank())
                        escapeHtml(ret.description)
                    else
                        "&mdash;"

                    append(
                        """
                        <tr>
                            <td style="padding: 4px; border: 1px solid #444;"><code>${escapeHtml(ret.name)}</code></td>
                            <td style="padding: 4px; border: 1px solid #444;">$desc</td>
                        </tr>
                        """.trimIndent()
                    )
                }
                append("</table>")
            }


            if (!parsed.snippetBefore.isNullOrBlank()) {
                val normalizedBefore = removeLeadingIndentation(parsed.snippetBefore!!)
                append("<br><b>Snippet Before:</b><pre>${escapeHtml(normalizedBefore)}</pre>")
            }
            if (!parsed.snippetAfter.isNullOrBlank()) {
                val normalizedAfter = removeLeadingIndentation(parsed.snippetAfter!!)
                append("<br><b>Snippet After:</b><pre>${escapeHtml(normalizedAfter)}</pre>")
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