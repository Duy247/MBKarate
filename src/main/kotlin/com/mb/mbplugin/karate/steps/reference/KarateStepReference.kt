package com.mb.mbplugin.karate.steps.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.mb.mbplugin.karate.psi.KarateFile
import com.mb.mbplugin.karate.psi.KaratePsiElement
import com.mb.mbplugin.karate.psi.KarateTokenTypes

class KarateStepReference(
    element: PsiElement,
    textRange: TextRange
) : PsiReferenceBase<PsiElement>(element, textRange), PsiPolyVariantReference {

    override fun resolve(): PsiElement? {
        val results = multiResolve(false)
        return if (results.size == 1) results[0].element else null
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        return ResolveCache.getInstance(element.project)
            .resolveWithCaching(this, MyResolver(), false, incompleteCode)
    }

    private fun multiResolveInner(): Array<ResolveResult> {
        val elementText = element.text
        val project = element.project
        val results = mutableListOf<ResolveResult>()

        // Search for step definitions in the current file
        val containingFile = element.containingFile
        if (containingFile is KarateFile) {
            val stepElements = PsiTreeUtil.findChildrenOfType(containingFile, KaratePsiElement::class.java)
                .filter { 
                    it.node.elementType == KarateTokenTypes.STEP_KEYWORD ||
                    it.node.elementType == KarateTokenTypes.ACTION_KEYWORD ||
                    it.node.elementType == KarateTokenTypes.VARIABLE ||
                    it.node.elementType == KarateTokenTypes.DECLARATION
                }
                .filter { it.text.contains(elementText) }

            stepElements.forEach { step ->
                results.add(object : ResolveResult {
                    override fun getElement(): PsiElement = step
                    override fun isValidResult(): Boolean = true
                })
            }
        }

        // Search globally for similar elements
        val searchHelper = PsiSearchHelper.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        
        try {
            searchHelper.processElementsWithWord(
                { element, _ ->
                    if (element is KaratePsiElement && 
                        element.text.contains(elementText) &&
                        element.containingFile is KarateFile) {
                        results.add(object : ResolveResult {
                            override fun getElement(): PsiElement = element
                            override fun isValidResult(): Boolean = true
                        })
                    }
                    true
                },
                scope,
                elementText,
                com.intellij.psi.search.UsageSearchContext.IN_CODE,
                true
            )
        } catch (e: Exception) {
            // Ignore search errors
        }

        return results.toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()

    private class MyResolver : ResolveCache.PolyVariantResolver<KarateStepReference> {
        override fun resolve(ref: KarateStepReference, incompleteCode: Boolean): Array<ResolveResult> {
            return ref.multiResolveInner()
        }
    }
}