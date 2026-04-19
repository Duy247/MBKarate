package com.mb.mbplugin.karate.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import com.mb.mbplugin.karate.documentation.KarateFeatureDocumentationProvider
import com.mb.mbplugin.karate.index.KarateFeatureData
import com.mb.mbplugin.karate.psi.KarateFile
import org.jetbrains.plugins.cucumber.psi.GherkinFileType
import org.jetbrains.plugins.cucumber.psi.GherkinLanguage
import java.io.File

class KarateTagCompletionContributor : CompletionContributor() {
    init {
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
        private val TAG_PATTERN = Regex("(?:^|\\s)@([\\p{L}_][\\p{L}\\p{N}_]*)", RegexOption.MULTILINE)
        private val DOC_PATTERN = Regex("\"\"\"\\s*@([A-Za-z0-9_]+_DOC)\\s*\\n([\\s\\S]*?)\"\"\"", RegexOption.MULTILINE)
        private val SOURCE_ROOTS = listOf("src/test/java", "src/main/java", "src/test/resources", 
                                         "src/main/resources", "src/test/kotlin", "src/main/kotlin")
    }
    
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val file = parameters.originalFile
        val offset = parameters.offset
        val textBeforeCursor = file.text.substring(0, offset)
        
        val trigger = findTrigger(textBeforeCursor, offset) ?: return
        
        when (trigger.type) {
            TriggerType.SHORT -> handleShortCompletion(trigger, parameters, result)
            TriggerType.TAG -> handleTagOrFileCompletion(trigger, parameters, result, true, false)
            TriggerType.FILE -> handleTagOrFileCompletion(trigger, parameters, result, false, true)
        }
    }
    
    private fun findTrigger(text: String, offset: Int): Trigger? {
        val lastExcl = text.lastIndexOf('!')
        val lastHash = text.lastIndexOf('#')
        val lastAmp = text.lastIndexOf('&')
        
        return when {
            lastAmp != -1 && offset - lastAmp <= 50 && lastAmp > maxOf(lastExcl, lastHash) -> {
                Trigger(TriggerType.SHORT, lastAmp, text.substring(lastAmp + 1))
            }
            lastExcl != -1 && offset - lastExcl <= 20 && (lastHash == -1 || lastExcl > lastHash) -> {
                if (lastExcl > 0 && text[lastExcl - 1].isLetterOrDigit()) return null
                if (!isValidTagContext(text, lastExcl)) return null
                val prefix = text.substring(lastExcl + 1).takeWhile { it.isLetterOrDigit() || it == '_' }
                Trigger(TriggerType.TAG, lastExcl, prefix)
            }
            lastHash != -1 && offset - lastHash <= 30 && (lastExcl == -1 || lastHash > lastExcl) -> {
                if (lastHash > 0 && text[lastHash - 1].let { it.isLetterOrDigit() || it == '_' }) return null
                if (!isValidFileContext(text, lastHash)) return null
                val prefix = text.substring(lastHash + 1).takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
                Trigger(TriggerType.FILE, lastHash, prefix)
            }
            else -> null
        }
    }
    
    private fun handleShortCompletion(trigger: Trigger, parameters: CompletionParameters, result: CompletionResultSet) {
        result.restartCompletionOnAnyPrefixChange()
        result.stopHere()
        
        val project = parameters.originalFile.project
        val docProvider = KarateFeatureDocumentationProvider()
        
        getAllFeatureFiles(project).forEach { vf ->
            val psiFile = PsiManager.getInstance(project).findFile(vf) as? KarateFile ?: return@forEach
            
            DOC_PATTERN.findAll(psiFile.text).forEach { match ->
                val scenarioTag = match.groupValues[1].removeSuffix("_DOC")
                val parsed = docProvider.parseDocDirectives(match.groupValues[2])
                val short = parsed.shortDescription ?: return@forEach
                
                if (trigger.prefix.isNotBlank() && trigger.prefix.contains(short, ignoreCase = true)) return@forEach
                
                val normPath = getRelativePath(project, vf).replace("\\", "/")
                val paramBlock = buildParamBlock(extractRequiredInputParams(parsed.params))
                
                val callLine = "* call read('classpath:$normPath@$scenarioTag')$paramBlock"
                val fullSuggestion = buildSuggestion(parsed.snippetBefore, callLine, parsed.snippetAfter)
                
                result.addElement(
                    LookupElementBuilder.create("&$short")
                        .withPresentableText(short)
                        .withTypeText("${vf.nameWithoutExtension}.feature@$scenarioTag", true)
                        .withTailText(" ($normPath)", true)
                        // Add lookup variants to improve tokenization/case-insensitive matching
                        .withLookupString(short)
                        .withLookupString(short.lowercase())
                        .withLookupString(short.uppercase())
                        .withLookupString("&$short")
                        .withInsertHandler { ctx, _ -> insertWithIndent(ctx, trigger.index, fullSuggestion, '&') }
                )
            }
        }
    }
    
    private fun handleTagOrFileCompletion(trigger: Trigger, parameters: CompletionParameters, 
                                         result: CompletionResultSet, isTag: Boolean, isFile: Boolean) {
        val project = parameters.originalFile.project
        val items = getAllMatchingItems(project, trigger.prefix, isTag, isFile)
        val docProvider = KarateFeatureDocumentationProvider()
        
        items.forEach { tagData ->
            val normPath = tagData.relativePath.replace("\\", "/")
            
            if (tagData.tag.isEmpty()) {
                addFeatureFileCompletion(result, tagData, normPath, isTag, isFile, trigger)
            } else {
                addTagCompletion(result, tagData, normPath, project, docProvider, isTag, isFile, trigger)
            }
        }
    }
    
    private fun addFeatureFileCompletion(result: CompletionResultSet, tagData: KarateFeatureData, 
                                        normPath: String, isTag: Boolean, isFile: Boolean, trigger: Trigger) {
        val suggestion = "* call read('classpath:$normPath')"
        val fileName = File(tagData.absolutePath).nameWithoutExtension
        
        result.addElement(
            LookupElementBuilder.create(fileName)
                .withPresentableText(fileName)
                .withLookupString(fileName)
                .withLookupString(fileName.lowercase())
                .withLookupString(fileName.uppercase())
                .withTypeText("$fileName.feature")
                .withTailText(" ($normPath)", true)
                .withInsertHandler { ctx, _ -> 
                    insertWithIndent(ctx, trigger.index, suggestion, if (isTag) '!' else '#')
                }
        )
    }
    
    private fun addTagCompletion(result: CompletionResultSet, tagData: KarateFeatureData, normPath: String,
                                project: Project, docProvider: KarateFeatureDocumentationProvider,
                                isTag: Boolean, isFile: Boolean, trigger: Trigger) {
        val vFile = findVirtualFile(tagData.absolutePath) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(vFile) as? KarateFile ?: return
        
        val docInfo = extractDocInfoWithShort(psiFile, tagData.tag, docProvider)
        
        val paramBlock = buildParamBlock(docInfo.paramInputs)
        
        val callLine = "* call read('classpath:$normPath@${tagData.tag}')$paramBlock"
        val fullSuggestion = buildSuggestion(docInfo.snippetBefore, callLine, docInfo.snippetAfter)
        
        val fileName = File(tagData.absolutePath).nameWithoutExtension
        
        // Build presentation text: {file_name} | {tag} | {@short}
        val presentableText = buildString {
            append("@" + tagData.tag)
            if (docInfo.shortDescription != null) {
                append(" | ")
                append(docInfo.shortDescription)
            }
        }
        
        result.addElement(
            LookupElementBuilder.create("!${tagData.tag}__${fileName}__${System.nanoTime()}")
                .withPresentableText(presentableText)
                .withLookupString("!${tagData.tag}")
                // also allow matching by tag name (case variants) and file name
                .withLookupString(tagData.tag)
                .withLookupString(tagData.tag.lowercase())
                .withLookupString(tagData.tag.uppercase())
                .withLookupString(fileName)
                .withTypeText("$fileName.feature")
                .withTailText(" ($normPath)", true)
                .withInsertHandler { ctx, _ -> 
                    insertWithIndent(ctx, trigger.index, fullSuggestion, if (isFile) '#' else '!')
                }
        )
    }
    
    private fun extractDocInfo(psiFile: KarateFile, tag: String, 
                              docProvider: KarateFeatureDocumentationProvider): Triple<String?, String?, List<String>> {
        val docText = docProvider.extractDocumentationFromFile(psiFile, tag)
        if (docText.isNullOrBlank()) return Triple(null, null, emptyList())
        
        val parsed = docProvider.parseDocDirectives(docText)
        val paramInputs = extractRequiredInputParams(parsed.params).map { it.name }
        
        return Triple(parsed.snippetBefore, parsed.snippetAfter, paramInputs)
    }
    
    private fun extractDocInfoWithShort(psiFile: KarateFile, tag: String, 
                                       docProvider: KarateFeatureDocumentationProvider): ExtractedDocInfo {
        val docText = docProvider.extractDocumentationFromFile(psiFile, tag)
        if (docText.isNullOrBlank()) return ExtractedDocInfo(null, null, emptyList(), null)
        
        val parsed = docProvider.parseDocDirectives(docText)
        val paramInputs = extractRequiredInputParams(parsed.params)
        
        return ExtractedDocInfo(parsed.snippetBefore, parsed.snippetAfter, paramInputs, parsed.shortDescription)
    }
    
    data class InputParam(
        val name: String,
        val defaultValue: String?
    )

    data class ExtractedDocInfo(
        val snippetBefore: String?,
        val snippetAfter: String?,
        val paramInputs: List<InputParam>,
        val shortDescription: String?
    )

    private fun extractRequiredInputParams(
        params: List<KarateFeatureDocumentationProvider.ParamEntry>
    ): List<InputParam> {
        return params.filter { param ->
            hasTag(param.tags, "input") && hasTag(param.tags, "required")
        }.map { param ->
            InputParam(param.name, extractDefaultTagValue(param.tags))
        }
    }

    private fun hasTag(tags: List<String>, expectedTag: String): Boolean {
        return tags.any { raw ->
            val trimmed = raw.trim()
            trimmed.equals(expectedTag, ignoreCase = true) ||
                trimmed.substringBefore(':').trim().equals(expectedTag, ignoreCase = true) ||
                trimmed.split('/').any { part -> part.trim().equals(expectedTag, ignoreCase = true) }
        }
    }

    private fun extractDefaultTagValue(tags: List<String>): String? {
        for (raw in tags) {
            val trimmed = raw.trim()
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) continue

            val key = trimmed.substring(0, separatorIndex).trim()
            if (key.equals("default", ignoreCase = true)) {
                return trimmed.substring(separatorIndex + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun buildParamBlock(params: List<InputParam>): String {
        if (params.isEmpty()) return ""
        return params.joinToString(", ", prefix = " { ", postfix = " }") { param ->
            val value = param.defaultValue ?: "''"
            "${param.name}: $value"
        }
    }
    
    private fun buildSuggestion(before: String?, main: String, after: String?): String = buildString {
        before?.trim()?.takeIf { it.isNotBlank() }?.let {
            append(convertTripleQuotes(removeIndent(it)))
            if (!it.endsWith("\n")) append("\n")
        }
        append(main)
        after?.trim()?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(convertTripleQuotes(removeIndent(it)))
        }
    }
    
    private fun convertTripleQuotes(text: String): String =
        text.replace("'''", "\"\"\"")
    
    private fun insertWithIndent(ctx: InsertionContext, triggerIndex: Int, suggestion: String, triggerChar: Char) {
        val doc = ctx.document
        doc.deleteString(ctx.startOffset, ctx.tailOffset)
        
        val lineNum = doc.getLineNumber(ctx.startOffset)
        val lineStart = doc.getLineStartOffset(lineNum)
        val lineText = doc.getText(TextRange(lineStart, ctx.startOffset))
        
        val charIndex = lineText.lastIndexOf(triggerChar)
        val replaceStart = if (charIndex >= 0) lineStart + charIndex else ctx.startOffset
        val baseIndent = lineText.takeWhile { it == ' ' || it == '\t' }
        
        // Find end of trigger pattern
        val currentText = doc.text
        var replaceEnd = replaceStart + 1
        while (replaceEnd < currentText.length && 
               (currentText[replaceEnd].isLetterOrDigit() || currentText[replaceEnd] == '_')) {
            replaceEnd++
        }
        
        doc.deleteString(replaceStart, replaceEnd)
        
        val lines = suggestion.lines()
        val indented = buildString {
            lines.forEachIndexed { i, line ->
                val trimmed = line.trimStart()
                if (i > 0) append("\n").append(baseIndent)
                append(trimmed)
            }
        }
        
        doc.insertString(replaceStart, indented)
    }
    
    private fun getAllMatchingItems(project: Project, prefix: String, 
                                   isTag: Boolean, isFile: Boolean): List<KarateFeatureData> {
        val result = mutableListOf<KarateFeatureData>()
        
        FileTypeIndex.getFiles(GherkinFileType.INSTANCE, GlobalSearchScope.allScope(project)).forEach { vf ->
            runCatching {
                val fileText = PsiManager.getInstance(project).findFile(vf)?.text 
                    ?: vf.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val relativePath = getRelativePath(project, vf)
                
                if (isTag) {
                    extractTags(fileText).filter { 
                        prefix.isEmpty() || it.contains(prefix, ignoreCase = true) 
                    }.forEach { tag ->
                        result.add(KarateFeatureData(relativePath, tag, vf.path))
                    }
                }
                
                if (isFile) {
                    val fileName = vf.nameWithoutExtension
                    if (prefix.isEmpty() || fileName.contains(prefix, ignoreCase = true)) {
                        result.add(KarateFeatureData(relativePath, "", vf.path))
                        extractTags(fileText).forEach { tag ->
                            result.add(KarateFeatureData(relativePath, tag, vf.path))
                        }
                    }
                }
            }
        }
        
        return result.distinctBy { "${it.absolutePath}@${it.tag}" }
    }
    
    private fun extractTags(text: String): List<String> = 
        TAG_PATTERN.findAll(text)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("param", true) && !it.equals("return", true) && !it.equals("short", true) && !it.equals("snippet_before", true) && !it.equals("snippet_after", true) && !it.endsWith("DOC") }
            .distinct()
            .toList()
    
    private fun getRelativePath(project: Project, file: VirtualFile): String {
        val projectPath = project.basePath ?: return file.path
        val filePath = file.path
        
        SOURCE_ROOTS.forEach { root ->
            val rootPath = "$projectPath/$root".replace("/", File.separator)
            if (filePath.startsWith(rootPath)) {
                return filePath.substring(rootPath.length + 1).replace(File.separator, "/")
            }
        }
        
        runCatching {
            com.intellij.openapi.module.ModuleManager.getInstance(project).modules.forEach { module ->
                com.intellij.openapi.roots.ModuleRootManager.getInstance(module).sourceRoots.forEach { root ->
                    if (filePath.startsWith(root.path)) {
                        return filePath.substring(root.path.length + 1).replace(File.separator, "/")
                    }
                }
            }
        }
        
        return if (filePath.startsWith(projectPath)) {
            filePath.substring(projectPath.length + 1).replace(File.separator, "/")
        } else file.name
    }
    
    private fun findVirtualFile(path: String): VirtualFile? =
        com.intellij.openapi.vfs.VfsUtil.findFileByIoFile(File(path), true)
            ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("jar://$path!/")
    
    private fun getAllFeatureFiles(project: Project): List<VirtualFile> =
        FileTypeIndex.getFiles(GherkinFileType.INSTANCE, GlobalSearchScope.allScope(project)).toList()
    
    private fun isValidTagContext(text: String, exclIndex: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', exclIndex - 1) + 1
        val prefix = text.substring(lineStart, exclIndex).trim()
        
        return prefix.isEmpty() ||
               Regex("""call\s+read\s*\(\s*['"]classpath:[^'"]*$""", RegexOption.IGNORE_CASE).containsMatchIn(prefix) ||
               Regex("""call\s+read\s*\(\s*['"]classpath:[^'"]*\.feature$""", RegexOption.IGNORE_CASE).containsMatchIn(prefix) ||
               Regex("""\.tags\s*\(\s*['"]$""", RegexOption.IGNORE_CASE).containsMatchIn(prefix) ||
               (prefix.startsWith("#") && (prefix.contains("tag", true) || prefix.contains("scenario", true)))
    }
    
    private fun isValidFileContext(text: String, hashIndex: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', hashIndex - 1) + 1
        val prefix = text.substring(lineStart, hashIndex).trim()
        
        return prefix.isEmpty() ||
               prefix.startsWith("#") ||
               Regex("""call\s+read\s*\(\s*['"]classpath:\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(prefix) ||
               Regex("""call\s+read\s*\(\s*['"]classpath:[^'"]*/$""", RegexOption.IGNORE_CASE).containsMatchIn(prefix) ||
               listOf("read", "load", "include", "import", "file", "feature", "call").any { 
                   prefix.contains(it, true) 
               }
    }
    
    private fun removeIndent(text: String): String =
        text.lines().joinToString("\n") { it.trimStart() }.trimEnd('\n', '\r')
    
    data class Trigger(val type: TriggerType, val index: Int, val prefix: String)
    enum class TriggerType { TAG, FILE, SHORT }
}
