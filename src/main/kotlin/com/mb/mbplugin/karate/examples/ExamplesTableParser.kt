package com.mb.mbplugin.karate.examples

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.plugins.cucumber.psi.GherkinTable
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow

/**
 * Parser for Examples table data
 */
object ExamplesTableParser {
    
    /**
     * Parse an Examples block starting from the Examples keyword
     * Returns a TableData object containing headers and rows
     */
    fun parseExamplesBlock(examplesKeyword: PsiElement): TableData {
        val headers = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        
        // Find the table following the Examples keyword
        var current: PsiElement? = examplesKeyword.nextSibling
        
        // Skip whitespace and find the table
        while (current != null) {
            if (current is GherkinTable) {
                // Parse the table
                val tableRows = current.children.filterIsInstance<GherkinTableRow>()
                
                if (tableRows.isNotEmpty()) {
                    // First row is headers
                    val headerRow = tableRows.first()
                    headers.addAll(parseTableRow(headerRow))
                    
                    // Remaining rows are data
                    for (i in 1 until tableRows.size) {
                        rows.add(parseTableRow(tableRows[i]))
                    }
                }
                break
            }
            
            // Stop if we hit another major element
            if (!current.text.isBlank() && current !is PsiWhiteSpace) {
                // Check if this is a colon after Examples
                if (current.text.trim() != ":") {
                    break
                }
            }
            
            current = current.nextSibling
        }
        
        return TableData(headers, rows, examplesKeyword)
    }
    
    /**
     * Parse a single table row into a list of cell values
     */
    private fun parseTableRow(row: GherkinTableRow): List<String> {
        val cells = mutableListOf<String>()
        
        // Get cell texts
        for (cell in row.psiCells) {
            cells.add(cell.text.trim())
        }
        
        return cells
    }
    
    /**
     * Data class representing table structure
     */
    data class TableData(
        val headers: MutableList<String>,
        val rows: MutableList<List<String>>,
        val examplesElement: PsiElement
    )
}
