package com.mb.mbplugin.karate.documentation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.mb.mbplugin.karate.documentation.KarateFeatureDocumentationProvider
import java.util.regex.Pattern
import com.intellij.lang.LanguageAnnotators
import org.jetbrains.plugins.cucumber.psi.GherkinLanguage

class KarateCallReadAnnotator : Annotator {
    init {
        LanguageAnnotators.INSTANCE.addExplicitExtension(GherkinLanguage.INSTANCE, this)
    }
    private val CALL_READ_PATTERN = Pattern.compile(
        "call\\s+read\\s*\\(\\s*['\"]classpath:([^'\"]+\\.feature)(@\\w+)?['\"]\\s*\\)(\\s*\\{([^}]*)\\})?",
        Pattern.CASE_INSENSITIVE
    )

    private val processedElements = mutableSetOf<Int>()
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Avoid duplicate processing
        val start = element.textOffset
        if (processedElements.contains(start)) return
        processedElements.add(start)

        // Only process if this element is part of a "call read(" line
        val textFragment = element.text
        if (!textFragment.contains("call read(")) return

        // --- 🧠 Combine sibling text fragments (handles <a>, <type>, etc.) ---
        val combinedText = buildString {
            var current: PsiElement? = element
            while (current != null && !current.text.contains("\n")) {
                append(current.text)
                current = current.nextSibling
            }
        }

        // --- Match the call read pattern ---
        val matcher = CALL_READ_PATTERN.matcher(combinedText)
        if (!matcher.find()) return

        val featurePath = matcher.group(1)
        val scenarioTag = matcher.group(2)?.substring(1)
        val paramsBlock = matcher.group(4) // contents inside { ... }

        if (featurePath.isNullOrBlank() || scenarioTag.isNullOrBlank() || paramsBlock == null) return

        val project = element.project
        val docProvider = KarateFeatureDocumentationProvider()
        val documentation = docProvider.findDocumentationForFeature(project, featurePath, scenarioTag) ?: return
        val parsed = docProvider.parseDocDirectives(documentation)

        val allowedParams = parsed.paramInputs.toSet()
        if (allowedParams.isEmpty()) return

        val providedParams = extractParamsFromBlock(paramsBlock)

       val missing = allowedParams - providedParams
        if (missing.isNotEmpty()) {
            val missingList = missing.joinToString(", ")
            holder.newAnnotation(
                HighlightSeverity.ERROR,
                "Missing required input parameter(s): $missingList"
            ).range(element.textRange).create()
        }
    }


    private fun extractParamsFromBlock(paramsText: String): List<String> {
        // Parse simple Karate-style key:value pairs
        val regex = Regex("""(\w+)\s*:""")
        return regex.findAll(paramsText).map { it.groupValues[1] }.toList()
    }
}
