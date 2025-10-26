package com.mb.mbplugin.karate.examples

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.plugins.cucumber.psi.GherkinTable

/**
 * Formatter for Examples tables - generates properly formatted Gherkin table text
 */
object ExamplesTableFormatter {
    
    /**
     * Format table data as Gherkin table text with proper alignment
     */
    fun formatTable(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty()) {
            return ""
        }
        
        // Calculate column widths
        val columnCount = headers.size
        val columnWidths = IntArray(columnCount) { 0 }
        
        // Check headers
        headers.forEachIndexed { index, header ->
            if (index < columnWidths.size) {
                columnWidths[index] = maxOf(columnWidths[index], header.length)
            }
        }
        
        // Check all rows
        rows.forEach { row ->
            row.forEachIndexed { index, cell ->
                if (index < columnWidths.size) {
                    columnWidths[index] = maxOf(columnWidths[index], cell.length)
                }
            }
        }
        
        // Build the formatted table
        val sb = StringBuilder()
        
        // Add header row
        sb.append("      | ")
        headers.forEachIndexed { index, header ->
            sb.append(header.padEnd(columnWidths[index]))
            sb.append(" | ")
        }
        sb.append("\n")
        
        // Add data rows
        rows.forEach { row ->
            sb.append("      | ")
            row.forEachIndexed { index, cell ->
                val width = if (index < columnWidths.size) columnWidths[index] else cell.length
                sb.append(cell.padEnd(width))
                sb.append(" | ")
            }
            sb.append("\n")
        }
        
        return sb.toString()
    }
    
    /**
     * Update the Examples table in the document
     */
    fun updateTableInDocument(
        examplesElement: PsiElement,
        headers: List<String>,
        rows: List<List<String>>
    ) {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runReadAction {
                val project = examplesElement.project
                val psiFile = examplesElement.containingFile
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runReadAction
                
                // Find the range to replace (must be in read action)
                val startOffset = findTableStartOffset(examplesElement, document)
                val endOffset = findTableEndOffset(examplesElement, document)
                
                if (startOffset != -1 && endOffset != -1) {
                    // Generate the new table text
                    val newTableText = formatTable(headers, rows)
                    
                    // Now perform the write action
                    ApplicationManager.getApplication().invokeLater {
                        WriteCommandAction.runWriteCommandAction(project) {
                            // Replace the old table with the new one
                            document.replaceString(startOffset, endOffset, newTableText)
                            
                            // Commit the document
                            PsiDocumentManager.getInstance(project).commitDocument(document)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Find the start offset of the table (after Examples:)
     */
    private fun findTableStartOffset(examplesElement: PsiElement, document: Document): Int {
        var current: PsiElement? = examplesElement.nextSibling
        
        // Skip whitespace and colon
        while (current != null) {
            if (current is GherkinTable) {
                return current.textRange.startOffset
            }
            
            // If we find the line after "Examples:", that's where we'll insert
            if (current is PsiWhiteSpace && current.text.contains("\n")) {
                // Return position after the newline
                val offset = current.textRange.startOffset
                val text = current.text
                val newlineIndex = text.indexOf("\n")
                return offset + newlineIndex + 1
            }
            
            current = current.nextSibling
        }
        
        // If no table found, insert after the Examples keyword line
        val line = document.getLineNumber(examplesElement.textRange.endOffset)
        return document.getLineEndOffset(line) + 1
    }
    
    /**
     * Find the end offset of the table
     */
    private fun findTableEndOffset(examplesElement: PsiElement, document: Document): Int {
        var current: PsiElement? = examplesElement.nextSibling
        
        while (current != null) {
            if (current is GherkinTable) {
                return current.textRange.endOffset
            }
            current = current.nextSibling
        }
        
        // If no table exists, return the same as start (will insert new table)
        return findTableStartOffset(examplesElement, document)
    }
}
