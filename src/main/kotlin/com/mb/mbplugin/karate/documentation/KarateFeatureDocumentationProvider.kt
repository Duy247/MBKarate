package com.mb.mbplugin.karate.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.mb.mbplugin.karate.index.KarateFeatureIndex
import com.mb.mbplugin.karate.psi.KarateFile
import java.util.regex.Pattern

class KarateFeatureDocumentationProvider : AbstractDocumentationProvider() {
    
    companion object {
        val CALL_READ_PATTERN: Pattern = Pattern.compile(
            "call\\s+read\\s*\\(\\s*['\"]classpath:([^'\"]+\\.feature)(@\\w+)?['\"]",
            Pattern.CASE_INSENSITIVE
        )
        
        private val INLINE_DOC_PATTERN = Pattern.compile(
            "\"\"\"\\s*@([A-Za-z0-9_]+_DOC)\\s*\\n([\\s\\S]*?)\"\"\"",
            Pattern.MULTILINE
        )
        
        private val PARAM_REGEX = Regex(
            """@param\s+((?:\{[^}]+})*)\s*(\w+)(?:\s*\n(?!\s*@param|\s*@return|\s*@snippet_before|\s*@snippet_after)([^\n]*))?""",
            RegexOption.MULTILINE
        )
        
        private val RETURN_REGEX = Regex(
            """@return\s+(\w+)(?:\s*\n(?!\s*@param|\s*@snippet_before|\s*@snippet_after|\s*@return)([^\n]*))?""",
            RegexOption.MULTILINE
        )
        
        private val SNIPPET_BEFORE_REGEX = Regex(
            "@snippet_before\\s+([\\s\\S]*?)(?=^\\s*@\\w+|\\Z)",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )
        
        private val SNIPPET_AFTER_REGEX = Regex(
            "@snippet_after\\s+([\\s\\S]*?)(?=^\\s*@\\w+|\\Z)",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )
        
        private val SHORT_REGEX = Regex(
            "^\\s*@short\\s+([^\\r\\n]+)\\s*(?:\\r?\\n|\\Z)",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )
        
        private val TAG_EXTRACTOR = Regex("\\{([^}]+)}")
        
        private val TAG_COLORS = mapOf(
            "required" to "#e53935",
            "optional" to "#1e88e5",
            "input" to "#43a047",
            "predefined" to "#fb8c00",
            "default" to "#fbf700ff"
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
    
    data class ParamEntry(val name: String, val tags: List<String>, val description: String)
    data class ReturnEntry(val name: String, val description: String)

    fun parseDocDirectives(documentation: String): DocParseResult {
        val params = PARAM_REGEX.findAll(documentation).map {
            val tags = TAG_EXTRACTOR.findAll(it.groupValues[1]).map { t -> t.groupValues[1] }.toList()
            ParamEntry(it.groupValues[2], tags, it.groupValues[3].trim())
        }.toList()

        val returns = RETURN_REGEX.findAll(documentation).map {
            ReturnEntry(it.groupValues[1], it.groups[2]?.value?.trim().orEmpty())
        }.toList()
        
        val snippetBefore = SNIPPET_BEFORE_REGEX.find(documentation)?.groupValues?.get(1)?.trim()
        val snippetAfter = SNIPPET_AFTER_REGEX.find(documentation)?.groupValues?.get(1)?.trim()
        val shortDesc = SHORT_REGEX.find(documentation)?.groupValues?.get(1)?.trim()

        val body = documentation
            .replace(PARAM_REGEX, "")
            .replace(RETURN_REGEX, "")
            .replace(SNIPPET_BEFORE_REGEX, "")
            .replace(SNIPPET_AFTER_REGEX, "")
            .replace(SHORT_REGEX, "")
            .trim()

        return DocParseResult(body, params, returns, snippetBefore, snippetAfter, shortDesc)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val (featurePath, scenarioTag) = findCallReadAtPosition(element, originalElement) ?: return null
        val documentation = findDocumentationForFeature(element!!.project, featurePath, scenarioTag)
        
        return documentation?.let {
            formatDocumentation(it, featurePath.substringAfterLast("/"), scenarioTag)
        } ?: buildNoDocMessage(featurePath.substringAfterLast("/"), scenarioTag)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val (featurePath, scenarioTag) = findCallReadAtPosition(element, originalElement) ?: return null
        val fileName = featurePath.substringAfterLast("/")
        return if (scenarioTag != null) "Feature: $fileName, Scenario: $scenarioTag" else "Feature: $fileName"
    }

    override fun getCustomDocumentationElement(
        editor: com.intellij.openapi.editor.Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int
    ): PsiElement? {
        var current = contextElement
        while (current != null) {
            if (current.text.contains("call read")) return current
            current = current.parent
        }
        return contextElement
    }

    fun findCallReadAtPosition(element: PsiElement?, originalElement: PsiElement?): Pair<String, String?>? {
        if (element == null || originalElement == null) return null
        
        val offset = originalElement.textOffset
        var current: PsiElement? = element
        
        while (current != null) {
            val matcher = CALL_READ_PATTERN.matcher(current.text)
            while (matcher.find()) {
                val matchStart = current.textOffset + matcher.start()
                val matchEnd = current.textOffset + matcher.end()
                
                if (offset in matchStart..matchEnd) {
                    return Pair(matcher.group(1), matcher.group(2)?.substring(1))
                }
            }
            current = current.parent
        }
        return null
    }

    fun findDocumentationForFeature(project: Project, featureFilePath: String, scenarioTag: String?): String? {
        val fileName = featureFilePath.substringAfterLast("/")
        
        return KarateFeatureIndex.getAllFeatureFiles(project)
            .filter { it.name == fileName }
            .firstNotNullOfOrNull { virtualFile ->
                (PsiManager.getInstance(project).findFile(virtualFile) as? KarateFile)?.let {
                    extractDocumentationFromFile(it, scenarioTag)
                }
            }
    }

    fun extractDocumentationFromFile(karateFile: KarateFile, scenarioTag: String?): String? {
        val matcher = INLINE_DOC_PATTERN.matcher(karateFile.text)
        
        if (scenarioTag != null) {
            val expectedDocVarName = "${scenarioTag}_DOC"
            while (matcher.find()) {
                if (matcher.group(1) == expectedDocVarName) {
                    return matcher.group(2).trim()
                }
            }
            return null
        }
        
        return if (matcher.find()) matcher.group(2).trim() else null
    }

    fun formatDocumentation(documentation: String, fileName: String, scenarioTag: String?): String {
        val parsed = parseDocDirectives(documentation.takeIf { it.isNotBlank() } ?: "No documentation content available")
        
        return buildString {
            append("<html><body style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;\">")
            
            parsed.shortDescription?.let {
                append("<pre style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; background-color: rgba(79, 195, 247, 0.08); font-style: italic; font-size: 1em; color: #b3e5fc;\">")
                append(escapeHtml(it))
                append("</pre><br>")
            }
            
            append("<pre style=\"font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; margin: 0; padding: 0;\">")
            append(escapeHtml(removeLeadingIndentation(parsed.body)))
            append("</pre><br>")

            if (parsed.params.isNotEmpty()) {
                append("<br><b>Parameters:</b>")
                append(createTable(listOf("Name", "Tags", "Description")))
                parsed.params.forEach { param ->
                    append(createParamRow(param))
                }
                append("</table>")
            }

            if (parsed.returns.isNotEmpty()) {
                append("<br><b>Returns:</b>")
                append(createTable(listOf("Name", "Description")))
                parsed.returns.forEach { ret ->
                    append(createReturnRow(ret))
                }
                append("</table>")
            }

            parsed.snippetBefore?.let {
                append("<br><b>Snippet Before:</b><pre>")
                append(escapeHtml(removeLeadingIndentation(it)))
                append("</pre>")
            }
            
            parsed.snippetAfter?.let {
                append("<br><b>Snippet After:</b><pre>")
                append(escapeHtml(removeLeadingIndentation(it)))
                append("</pre>")
            }

            append("</body></html>")
        }
    }
    
    private fun buildNoDocMessage(fileName: String, scenarioTag: String?): String = buildString {
        append("<html><body><br><i>")
        if (scenarioTag != null) {
            append("No documentation found for scenario '@$scenarioTag'.</i><br>")
            append("<i>Expected documentation variable: <code>${scenarioTag}_DOC</code></i>")
        } else {
            append("No documentation found for this feature.</i>")
        }
        append("</body></html>")
    }
    
    private fun createTable(headers: List<String>): String = buildString {
        append("<table style=\"border-collapse: collapse; width: 500px; margin-top: 4px; white-space: nowrap;\">")
        append("<tr style=\"background-color: #2b2b2b; color: #ffffff;\">")
        headers.forEach { header ->
            append("<th style=\"text-align: left; padding: 4px; border: 1px solid #444;\">$header</th>")
        }
        append("</tr>")
    }
    
    private fun createParamRow(param: ParamEntry): String = buildString {
        val tagText = param.tags.joinToString(" ") { rawTag ->
            rawTag.split("/", ":").joinToString("/") { tag ->
                val cleanTag = tag.trim()
                val color = TAG_COLORS[cleanTag.lowercase()] ?: "#757575"
                "<b><font color=\"$color\">${escapeHtml(cleanTag)}</font></b>"
            }
        }
        
        val desc = if (param.description.isNotBlank()) escapeHtml(param.description) else "&mdash;"
        
        append("<tr>")
        append("<td style=\"padding: 4px; border: 1px solid #444;\"><code>${escapeHtml(param.name)}</code></td>")
        append("<td style=\"padding: 4px; border: 1px solid #444; white-space: wrap;\">$tagText</td>")
        append("<td style=\"padding: 4px; border: 1px solid #444; white-space: wrap;\">$desc</td>")
        append("</tr>")
    }
    
    private fun createReturnRow(ret: ReturnEntry): String = buildString {
        val desc = if (ret.description.isNotBlank()) escapeHtml(ret.description) else "&mdash;"
        
        append("<tr>")
        append("<td style=\"padding: 4px; border: 1px solid #444;\"><code>${escapeHtml(ret.name)}</code></td>")
        append("<td style=\"padding: 4px; border: 1px solid #444;\">$desc</td>")
        append("</tr>")
    }
    
    fun removeLeadingIndentation(text: String): String =
        text.lines().joinToString("\n") { it.trimStart() }

    fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("&lt;b&gt;", "<b>")
            .replace("&lt;/b&gt;", "</b>")
            .replace("&lt;i&gt;", "<i>")
            .replace("&lt;/i&gt;", "</i>")
            .replace("&lt;br&gt;", "<br>")
            .replace("&lt;br/&gt;", "<br/>")

    fun extractParamInputsFromDoc(docText: String): List<String> =
        Regex("@param_input\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(docText).map { it.groupValues[1] }.toList()
}