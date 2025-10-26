package com.mb.mbplugin.karate.examples

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.JTableHeader
import javax.swing.Timer

/**
 * Panel containing the spreadsheet-like table editor for Examples
 */
class ExamplesTableEditorPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private var tableModel: DefaultTableModel = DefaultTableModel()
    private val table: JBTable = JBTable(tableModel)
    private var currentExamplesElement: PsiElement? = null
    private var updateTimer: Timer? = null
    private var rowHeaderTable: JTable? = null
    private var scrollPane: JBScrollPane? = null
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        // Configure table
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setDefaultEditor(Any::class.java, DefaultCellEditor(JTextField()))
        table.fillsViewportHeight = true
        table.dragEnabled = true
        table.dropMode = DropMode.INSERT_ROWS
        
        // Enable column reordering
        table.tableHeader.reorderingAllowed = true
        
        // Custom table header with add column buttons
        table.tableHeader = createCustomTableHeader()
        
        // Add keyboard shortcuts for copy/paste
        setupKeyboardShortcuts()
        
        // Add right-click context menu
        setupContextMenu()
        
        // Add header context menu for renaming
        setupHeaderContextMenu()
        
        // Create scroll pane
        scrollPane = JBScrollPane(table)
        
        // Add components
        add(scrollPane!!, BorderLayout.CENTER)
        
        // Add row number column with add buttons (after scroll pane is created)
        setupRowHeaderColumn(scrollPane!!)
        
        // Add table model listener to update document in real-time with debouncing
        tableModel.addTableModelListener {
            scheduleDocumentUpdate()
            // Update row header to reflect new row count
            updateRowHeader()
        }
        
        // Setup drag and drop for rows
        setupRowDragAndDrop()
        
        // Setup column reorder listener
        setupColumnReorderListener()
    }
    
    /**
     * Create custom table header with add column button on the right
     */
    private fun createCustomTableHeader(): JTableHeader {
        val header = object : JTableHeader(table.columnModel) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                
                // Draw + button at the right edge
                val g2 = g as Graphics2D
                g2.color = Color(100, 150, 255)
                
                val buttonX = width - 30
                val buttonY = (height - 20) / 2
                
                g2.fillRect(buttonX, buttonY, 20, 20)
                g2.color = Color.WHITE
                g2.drawString("+", buttonX + 6, buttonY + 15)
            }
        }
        
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val buttonX = header.width - 30
                val buttonY = (header.height - 20) / 2
                
                if (e.x >= buttonX && e.x <= buttonX + 20 && 
                    e.y >= buttonY && e.y <= buttonY + 20) {
                    addColumn()
                }
            }
        })
        
        header.cursor = Cursor(Cursor.DEFAULT_CURSOR)
        return header
    }
    
    /**
     * Setup row header column with add row buttons
     */
    private fun setupRowHeaderColumn(scrollPane: JBScrollPane) {
        rowHeaderTable = JTable(object : DefaultTableModel() {
            override fun getRowCount() = table.model.rowCount
            override fun getColumnCount() = 1
            override fun getValueAt(row: Int, column: Int) = "+"
            override fun isCellEditable(row: Int, column: Int) = false
        })
        
        rowHeaderTable!!.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                label.horizontalAlignment = CENTER
                label.background = Color(240, 240, 240)
                label.foreground = Color(100, 150, 255)
                label.font = Font(label.font.name, Font.BOLD, 14)
                label.border = BorderFactory.createLineBorder(Color(200, 200, 200))
                return label
            }
        })
        
        rowHeaderTable!!.preferredScrollableViewportSize = Dimension(25, 0)
        rowHeaderTable!!.setShowGrid(false)
        rowHeaderTable!!.columnModel.getColumn(0).preferredWidth = 25
        rowHeaderTable!!.columnModel.getColumn(0).maxWidth = 25
        rowHeaderTable!!.columnModel.getColumn(0).minWidth = 25
        rowHeaderTable!!.intercellSpacing = Dimension(0, 0)
        
        // Synchronize row heights
        syncRowHeights()
        
        rowHeaderTable!!.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = rowHeaderTable!!.rowAtPoint(e.point)
                if (row >= 0) {
                    addRowAt(row + 1)
                }
            }
        })
        
        scrollPane.setRowHeaderView(rowHeaderTable)
    }
    
    /**
     * Synchronize row heights between main table and row header
     */
    private fun syncRowHeights() {
        // Set all rows in row header to match main table heights
        for (row in 0 until minOf(table.rowCount, rowHeaderTable?.rowCount ?: 0)) {
            rowHeaderTable?.setRowHeight(row, table.getRowHeight(row))
        }
    }
    
    /**
     * Update row header count without recreating it
     */
    private fun updateRowHeader() {
        rowHeaderTable?.let { header ->
            (header.model as DefaultTableModel).fireTableDataChanged()
            syncRowHeights()
            header.revalidate()
            header.repaint()
        }
    }
    
    /**
     * Setup drag and drop for reordering rows
     */
    private fun setupRowDragAndDrop() {
        table.transferHandler = object : TransferHandler() {
            private var draggedRow = -1
            
            override fun getSourceActions(c: JComponent): Int = MOVE
            
            override fun createTransferable(c: JComponent): Transferable? {
                draggedRow = table.selectedRow
                return object : Transferable {
                    override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor)
                    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor
                    override fun getTransferData(flavor: DataFlavor) = "row"
                }
            }
            
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor)
            }
            
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                
                val dropLocation = support.dropLocation
                if (dropLocation is JTable.DropLocation) {
                    val dropRow = dropLocation.row
                    if (draggedRow != -1 && dropRow != -1 && draggedRow != dropRow) {
                        moveRow(draggedRow, dropRow)
                        return true
                    }
                }
                return false
            }
        }
    }
    
    /**
     * Move a row from one position to another
     */
    private fun moveRow(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= tableModel.rowCount) return
        
        val targetIndex = if (toIndex > fromIndex) toIndex - 1 else toIndex
        
        // Extract the row data
        val rowData = mutableListOf<Any?>()
        for (col in 0 until tableModel.columnCount) {
            rowData.add(tableModel.getValueAt(fromIndex, col))
        }
        
        // Remove the row
        tableModel.removeRow(fromIndex)
        
        // Insert at new position
        val finalIndex = if (targetIndex > tableModel.rowCount) tableModel.rowCount else targetIndex
        tableModel.insertRow(finalIndex, rowData.toTypedArray())
        
        // Select the moved row
        table.setRowSelectionInterval(finalIndex, finalIndex)
        
        scheduleDocumentUpdate()
    }
    
    /**
     * Setup right-click context menu for removing rows/columns
     */
    private fun setupContextMenu() {
        val popupMenu = JPopupMenu()
        
        val removeRowItem = JMenuItem("Remove Row")
        removeRowItem.addActionListener {
            val selectedRow = table.selectedRow
            if (selectedRow != -1) {
                removeRow(selectedRow)
            }
        }
        
        val removeColumnItem = JMenuItem("Remove Column")
        removeColumnItem.addActionListener {
            val selectedCol = table.selectedColumn
            if (selectedCol != -1) {
                removeColumn(selectedCol)
            }
        }
        
        popupMenu.add(removeRowItem)
        popupMenu.add(removeColumnItem)
        
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showPopup(e)
                }
            }
            
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showPopup(e)
                }
            }
            
            private fun showPopup(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                
                if (row >= 0 && col >= 0) {
                    table.setRowSelectionInterval(row, row)
                    table.setColumnSelectionInterval(col, col)
                    popupMenu.show(e.component, e.x, e.y)
                }
            }
        })
    }
    
    /**
     * Setup right-click context menu for header to rename columns
     */
    private fun setupHeaderContextMenu() {
        val headerPopup = JPopupMenu()
        val renameItem = JMenuItem("Rename Column")
        
        renameItem.addActionListener {
            val selectedColumn = table.selectedColumn
            if (selectedColumn != -1) {
                renameColumn(selectedColumn)
            }
        }
        
        headerPopup.add(renameItem)
        
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showHeaderPopup(e)
                }
            }
            
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showHeaderPopup(e)
                }
            }
            
            private fun showHeaderPopup(e: MouseEvent) {
                val col = table.columnAtPoint(Point(e.x, 0))
                if (col >= 0) {
                    table.setColumnSelectionInterval(col, col)
                    headerPopup.show(e.component, e.x, e.y)
                }
            }
        })
    }
    
    /**
     * Setup listener for column reordering
     */
    private fun setupColumnReorderListener() {
        table.columnModel.addColumnModelListener(object : javax.swing.event.TableColumnModelListener {
            override fun columnAdded(e: javax.swing.event.TableColumnModelEvent?) {}
            override fun columnRemoved(e: javax.swing.event.TableColumnModelEvent?) {}
            override fun columnSelectionChanged(e: javax.swing.event.ListSelectionEvent?) {}
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent?) {}
            
            override fun columnMoved(e: javax.swing.event.TableColumnModelEvent?) {
                if (e != null && e.fromIndex != e.toIndex) {
                    // Column was reordered, update the model to match
                    SwingUtilities.invokeLater {
                        reorderColumnsInModel()
                        scheduleDocumentUpdate()
                    }
                }
            }
        })
    }
    
    /**
     * Reorder columns in the table model to match the view order
     */
    private fun reorderColumnsInModel() {
        val columnCount = table.columnCount
        if (columnCount == 0) return
        
        // Get current view order
        val viewOrder = IntArray(columnCount) { table.convertColumnIndexToModel(it) }
        
        // Create new model with reordered columns
        val newModel = DefaultTableModel()
        
        // Add columns in new order
        for (viewCol in viewOrder) {
            newModel.addColumn(tableModel.getColumnName(viewCol))
        }
        
        // Add rows with reordered data
        for (row in 0 until tableModel.rowCount) {
            val rowData = mutableListOf<Any?>()
            for (viewCol in viewOrder) {
                rowData.add(tableModel.getValueAt(row, viewCol))
            }
            newModel.addRow(rowData.toTypedArray())
        }
        
        // Replace the model
        tableModel = newModel
        table.model = tableModel
        
        // Re-add listener
        tableModel.addTableModelListener {
            scheduleDocumentUpdate()
            updateRowHeader()
        }
    }
    
    /**
     * Rename a column
     */
    private fun renameColumn(columnIndex: Int) {
        val currentName = tableModel.getColumnName(columnIndex)
        val newName = JOptionPane.showInputDialog(
            this,
            "Enter new column name:",
            currentName
        )
        
        if (newName != null && newName.isNotBlank() && newName != currentName) {
            // Get all column names
            val columnNames = mutableListOf<String>()
            for (i in 0 until tableModel.columnCount) {
                columnNames.add(if (i == columnIndex) newName else tableModel.getColumnName(i))
            }
            
            // Get all row data
            val rowsData = mutableListOf<Array<Any?>>()
            for (row in 0 until tableModel.rowCount) {
                val rowData = Array<Any?>(tableModel.columnCount) { col ->
                    tableModel.getValueAt(row, col)
                }
                rowsData.add(rowData)
            }
            
            // Create new model with renamed column
            val newModel = DefaultTableModel()
            columnNames.forEach { newModel.addColumn(it) }
            rowsData.forEach { newModel.addRow(it) }
            
            // Replace model
            tableModel = newModel
            table.model = tableModel
            
            // Re-add listener
            tableModel.addTableModelListener {
                scheduleDocumentUpdate()
                updateRowHeader()
            }
            
            scheduleDocumentUpdate()
        }
    }
    
    private fun setupKeyboardShortcuts() {
        // Handle Ctrl+V for paste
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.isControlDown && e.keyCode == KeyEvent.VK_V) {
                    pasteFromClipboard()
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE) {
                    deleteSelectedCells()
                    e.consume()
                }
            }
        })
    }
    
    private fun pasteFromClipboard() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null)
            
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val data = contents.getTransferData(DataFlavor.stringFlavor) as String
                val rows = data.split("\n")
                
                val selectedRow = table.selectedRow
                val selectedCol = table.selectedColumn
                
                if (selectedRow != -1 && selectedCol != -1) {
                    rows.forEachIndexed { rowIndex, rowData ->
                        val cells = rowData.split("\t")
                        cells.forEachIndexed { colIndex, cellValue ->
                            val targetRow = selectedRow + rowIndex
                            val targetCol = selectedCol + colIndex
                            
                            // Expand table if needed
                            while (tableModel.rowCount <= targetRow) {
                                addRowSilently()
                            }
                            while (tableModel.columnCount <= targetCol) {
                                addColumnSilently("col${tableModel.columnCount + 1}")
                            }
                            
                            if (targetRow < tableModel.rowCount && targetCol < tableModel.columnCount) {
                                tableModel.setValueAt(cellValue.trim(), targetRow, targetCol)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun deleteSelectedCells() {
        val selectedRows = table.selectedRows
        val selectedCols = table.selectedColumns
        
        for (row in selectedRows) {
            for (col in selectedCols) {
                if (row < tableModel.rowCount && col < tableModel.columnCount) {
                    tableModel.setValueAt("", row, col)
                }
            }
        }
    }
    
    private fun addColumn() {
        val columnName = JOptionPane.showInputDialog(
            this,
            "Enter column name:",
            "Add Column",
            JOptionPane.PLAIN_MESSAGE
        )
        
        if (columnName != null && columnName.isNotBlank()) {
            addColumnSilently(columnName)
            scheduleDocumentUpdate()
        }
    }
    
    private fun addColumnSilently(columnName: String) {
        tableModel.addColumn(columnName)
    }
    
    private fun addRow() {
        addRowAt(tableModel.rowCount)
    }
    
    private fun addRowAt(index: Int) {
        val rowData = Array(tableModel.columnCount) { "" }
        tableModel.insertRow(index, rowData)
        scheduleDocumentUpdate()
    }
    
    private fun addRowSilently() {
        val rowData = Array(tableModel.columnCount) { "" }
        tableModel.addRow(rowData)
    }
    
    private fun removeColumn(columnIndex: Int) {
        if (columnIndex < 0 || columnIndex >= tableModel.columnCount) return
        
        // Convert table model to remove column
        val newModel = DefaultTableModel()
        
        // Add columns except the selected one
        for (i in 0 until tableModel.columnCount) {
            if (i != columnIndex) {
                newModel.addColumn(tableModel.getColumnName(i))
            }
        }
        
        // Add rows with data except selected column
        for (row in 0 until tableModel.rowCount) {
            val rowData = mutableListOf<Any>()
            for (col in 0 until tableModel.columnCount) {
                if (col != columnIndex) {
                    rowData.add(tableModel.getValueAt(row, col) ?: "")
                }
            }
            newModel.addRow(rowData.toTypedArray())
        }
        
        tableModel = newModel
        table.model = tableModel
        
        // Re-add listener after setting new model
        tableModel.addTableModelListener {
            scheduleDocumentUpdate()
        }
        
        scheduleDocumentUpdate()
    }
    
    private fun removeRow(rowIndex: Int) {
        if (rowIndex >= 0 && rowIndex < tableModel.rowCount) {
            tableModel.removeRow(rowIndex)
            scheduleDocumentUpdate()
        }
    }
    
    /**
     * Load table data from parsed Examples block
     */
    fun loadTableData(data: ExamplesTableParser.TableData, examplesElement: PsiElement) {
        currentExamplesElement = examplesElement
        
        // Clear existing model
        tableModel = DefaultTableModel()
        
        // Add columns (headers)
        data.headers.forEach { header ->
            tableModel.addColumn(header)
        }
        
        // Add rows
        data.rows.forEach { row ->
            // Pad row to match column count
            val paddedRow = row.toMutableList()
            while (paddedRow.size < data.headers.size) {
                paddedRow.add("")
            }
            tableModel.addRow(paddedRow.toTypedArray())
        }
        
        // If no data, create a default empty row
        if (tableModel.rowCount == 0 && tableModel.columnCount > 0) {
            addRowSilently()
        }
        
        // If no columns, add a default column
        if (tableModel.columnCount == 0) {
            tableModel.addColumn("column1")
            addRowSilently()
        }
        
        table.model = tableModel
        
        // Re-add listener after setting new model
        tableModel.addTableModelListener {
            scheduleDocumentUpdate()
            updateRowHeader()
        }
        
        // Update row header
        updateRowHeader()
    }
    
    /**
     * Schedule a document update with debouncing to avoid excessive updates
     */
    private fun scheduleDocumentUpdate() {
        updateTimer?.stop()
        updateTimer = Timer(300) {
            updateDocument()
        }
        updateTimer?.isRepeats = false
        updateTimer?.start()
    }
    
    /**
     * Update the document with current table data
     */
    private fun updateDocument() {
        val examplesElement = currentExamplesElement ?: return
        
        // Extract headers and rows from table model
        val headers = mutableListOf<String>()
        for (i in 0 until tableModel.columnCount) {
            headers.add(tableModel.getColumnName(i))
        }
        
        val rows = mutableListOf<List<String>>()
        for (i in 0 until tableModel.rowCount) {
            val row = mutableListOf<String>()
            for (j in 0 until tableModel.columnCount) {
                val value = tableModel.getValueAt(i, j)?.toString() ?: ""
                row.add(value)
            }
            rows.add(row)
        }
        
        // Update the document
        ExamplesTableFormatter.updateTableInDocument(examplesElement, headers, rows)
    }
}
