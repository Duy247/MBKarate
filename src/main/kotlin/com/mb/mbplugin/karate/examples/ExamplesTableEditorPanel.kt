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
import javax.swing.table.*

class ExamplesTableEditorPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private var tableModel = DefaultTableModel()
    private val table = JBTable(tableModel).apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        setDefaultEditor(Any::class.java, DefaultCellEditor(JTextField()))
        fillsViewportHeight = true
        dragEnabled = true
        dropMode = DropMode.INSERT_ROWS
    }
    private var currentExamplesElement: PsiElement? = null
    private var updateTimer: Timer? = null
    private var rowHeaderTable: JTable? = null
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        table.tableHeader = createCustomTableHeader().apply { reorderingAllowed = true }
        setupKeyboardShortcuts()
        setupContextMenu()
        setupHeaderContextMenu()
        setupRowDragAndDrop()
        setupColumnReorderListener()
        
        val scrollPane = JBScrollPane(table)
        add(scrollPane, BorderLayout.CENTER)
        setupRowHeaderColumn(scrollPane)
        
        tableModel.addTableModelListener {
            scheduleDocumentUpdate()
            updateRowHeader()
        }
    }
    
    private fun createCustomTableHeader() = object : JTableHeader(table.columnModel) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            (g as Graphics2D).apply {
                color = Color(100, 150, 255)
                val x = width - 30
                val y = (height - 20) / 2
                fillRect(x, y, 20, 20)
                color = Color.WHITE
                drawString("+", x + 6, y + 15)
            }
        }
    }.apply {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val x = width - 30
                val y = (height - 20) / 2
                if (e.x in x..(x + 20) && e.y in y..(y + 20)) addColumn()
            }
        })
    }
    
    private fun setupRowHeaderColumn(scrollPane: JBScrollPane) {
        rowHeaderTable = JTable(object : DefaultTableModel() {
            override fun getRowCount() = table.model.rowCount
            override fun getColumnCount() = 1
            override fun getValueAt(row: Int, col: Int) = "+"
            override fun isCellEditable(row: Int, col: Int) = false
        }).apply {
            setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(table: JTable, value: Any?, 
                    isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int) =
                    (super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col) as JLabel).apply {
                        horizontalAlignment = CENTER
                        background = Color(240, 240, 240)
                        foreground = Color(100, 150, 255)
                        font = Font(font.name, Font.BOLD, 14)
                        border = BorderFactory.createLineBorder(Color(200, 200, 200))
                    }
            })
            preferredScrollableViewportSize = Dimension(25, 0)
            setShowGrid(false)
            columnModel.getColumn(0).apply { preferredWidth = 25; maxWidth = 25; minWidth = 25 }
            intercellSpacing = Dimension(0, 0)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    rowAtPoint(e.point).takeIf { it >= 0 }?.let { addRowAt(it + 1) }
                }
            })
        }
        
        scrollPane.setRowHeaderView(rowHeaderTable)
        syncRowHeights()
    }
    
    private fun syncRowHeights() {
        (0 until minOf(table.rowCount, rowHeaderTable?.rowCount ?: 0)).forEach { 
            rowHeaderTable?.setRowHeight(it, table.getRowHeight(it))
        }
    }
    
    private fun updateRowHeader() {
        rowHeaderTable?.apply {
            (model as DefaultTableModel).fireTableDataChanged()
            syncRowHeights()
            revalidate()
            repaint()
        }
    }
    
    private fun setupRowDragAndDrop() {
        table.transferHandler = object : TransferHandler() {
            private var draggedRow = -1
            
            override fun getSourceActions(c: JComponent) = MOVE
            
            override fun createTransferable(c: JComponent) = object : Transferable {
                init { draggedRow = table.selectedRow }
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor
                override fun getTransferData(flavor: DataFlavor) = "row"
            }
            
            override fun canImport(support: TransferSupport) = 
                support.isDataFlavorSupported(DataFlavor.stringFlavor)
            
            override fun importData(support: TransferSupport): Boolean {
                (support.dropLocation as? JTable.DropLocation)?.let { loc ->
                    if (draggedRow != -1 && loc.row != -1 && draggedRow != loc.row) {
                        moveRow(draggedRow, loc.row)
                        return true
                    }
                }
                return false
            }
        }
    }
    
    private fun moveRow(from: Int, to: Int) {
        if (from !in 0 until tableModel.rowCount || to < 0) return
        
        val target = if (to > from) to - 1 else to
        val rowData = (0 until tableModel.columnCount).map { tableModel.getValueAt(from, it) }
        
        tableModel.removeRow(from)
        tableModel.insertRow(target.coerceAtMost(tableModel.rowCount), rowData.toTypedArray())
        table.setRowSelectionInterval(target.coerceAtMost(tableModel.rowCount - 1), target.coerceAtMost(tableModel.rowCount - 1))
        scheduleDocumentUpdate()
    }
    
    private fun setupContextMenu() {
        val popup = JPopupMenu().apply {
            add(JMenuItem("Remove Row").apply { 
                addActionListener { table.selectedRow.takeIf { it != -1 }?.let(::removeRow) }
            })
            add(JMenuItem("Remove Column").apply {
                addActionListener { table.selectedColumn.takeIf { it != -1 }?.let(::removeColumn) }
            })
        }
        
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = if (e.isPopupTrigger) showPopup(e) else Unit
            override fun mouseReleased(e: MouseEvent) = if (e.isPopupTrigger) showPopup(e) else Unit
            
            private fun showPopup(e: MouseEvent) {
                val r = table.rowAtPoint(e.point)
                val c = table.columnAtPoint(e.point)
                if (r >= 0 && c >= 0) {
                    table.setRowSelectionInterval(r, r)
                    table.setColumnSelectionInterval(c, c)
                    popup.show(e.component, e.x, e.y)
                }
            }
        })
    }
    
    private fun setupHeaderContextMenu() {
        val popup = JPopupMenu().apply {
            add(JMenuItem("Rename Column").apply {
                addActionListener { table.selectedColumn.takeIf { it != -1 }?.let(::renameColumn) }
            })
        }
        
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = if (e.isPopupTrigger) showPopup(e) else Unit
            override fun mouseReleased(e: MouseEvent) = if (e.isPopupTrigger) showPopup(e) else Unit
            
            private fun showPopup(e: MouseEvent) {
                table.columnAtPoint(Point(e.x, 0)).takeIf { it >= 0 }?.let { c ->
                    table.setColumnSelectionInterval(c, c)
                    popup.show(e.component, e.x, e.y)
                }
            }
        })
    }
    
    private fun setupColumnReorderListener() {
        table.columnModel.addColumnModelListener(object : javax.swing.event.TableColumnModelListener {
            override fun columnAdded(e: javax.swing.event.TableColumnModelEvent?) {}
            override fun columnRemoved(e: javax.swing.event.TableColumnModelEvent?) {}
            override fun columnSelectionChanged(e: javax.swing.event.ListSelectionEvent?) {}
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent?) {}
            
            override fun columnMoved(e: javax.swing.event.TableColumnModelEvent?) {
                e?.takeIf { it.fromIndex != it.toIndex }?.let {
                    SwingUtilities.invokeLater {
                        reorderColumnsInModel()
                        scheduleDocumentUpdate()
                    }
                }
            }
        })
    }
    
    private fun reorderColumnsInModel() {
        if (table.columnCount == 0) return
        
        val viewOrder = IntArray(table.columnCount) { table.convertColumnIndexToModel(it) }
        tableModel = DefaultTableModel().apply {
            viewOrder.forEach { addColumn(tableModel.getColumnName(it)) }
            (0 until tableModel.rowCount).forEach { r ->
                addRow(viewOrder.map { tableModel.getValueAt(r, it) }.toTypedArray())
            }
        }
        table.model = tableModel
        tableModel.addTableModelListener {
            scheduleDocumentUpdate()
            updateRowHeader()
        }
    }
    
    private fun renameColumn(idx: Int) {
        JOptionPane.showInputDialog(this, "Enter new column name:", tableModel.getColumnName(idx))
            ?.takeIf { it.isNotBlank() && it != tableModel.getColumnName(idx) }?.let { newName ->
                tableModel = DefaultTableModel().apply {
                    (0 until tableModel.columnCount).forEach { 
                        addColumn(if (it == idx) newName else tableModel.getColumnName(it))
                    }
                    (0 until tableModel.rowCount).forEach { r ->
                        addRow(Array(tableModel.columnCount) { c -> tableModel.getValueAt(r, c) })
                    }
                }
                table.model = tableModel
                tableModel.addTableModelListener {
                    scheduleDocumentUpdate()
                    updateRowHeader()
                }
                scheduleDocumentUpdate()
            }
    }
    
    private fun setupKeyboardShortcuts() {
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.isControlDown && e.keyCode == KeyEvent.VK_V -> pasteFromClipboard()
                    e.keyCode in listOf(KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE) -> deleteSelectedCells()
                    else -> return
                }
                e.consume()
            }
        })
    }
    
    private fun pasteFromClipboard() {
        runCatching {
            val data = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                ?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
                ?.getTransferData(DataFlavor.stringFlavor) as? String ?: return
            
            val (sr, sc) = table.selectedRow to table.selectedColumn
            if (sr == -1 || sc == -1) return
            
            data.split("\n").forEachIndexed { ri, row ->
                row.split("\t").forEachIndexed { ci, cell ->
                    val tr = sr + ri
                    val tc = sc + ci
                    while (tableModel.rowCount <= tr) addRowSilently()
                    while (tableModel.columnCount <= tc) addColumnSilently("col${tableModel.columnCount + 1}")
                    tableModel.setValueAt(cell.trim(), tr, tc)
                }
            }
        }
    }
    
    private fun deleteSelectedCells() {
        table.selectedRows.forEach { r ->
            table.selectedColumns.forEach { c ->
                if (r < tableModel.rowCount && c < tableModel.columnCount) {
                    tableModel.setValueAt("", r, c)
                }
            }
        }
    }
    
    private fun addColumn() {
        JOptionPane.showInputDialog(this, "Enter column name:", "Add Column", JOptionPane.PLAIN_MESSAGE)
            ?.takeIf { it.isNotBlank() }?.let {
                addColumnSilently(it)
                scheduleDocumentUpdate()
            }
    }
    
    private fun addColumnSilently(name: String) = tableModel.addColumn(name)
    
    private fun addRowAt(idx: Int) {
        tableModel.insertRow(idx, Array(tableModel.columnCount) { "" })
        scheduleDocumentUpdate()
    }
    
    private fun addRowSilently() = tableModel.addRow(Array(tableModel.columnCount) { "" })
    
    private fun removeColumn(idx: Int) {
        if (idx !in 0 until tableModel.columnCount) return
        
        tableModel = DefaultTableModel().apply {
            (0 until tableModel.columnCount).filter { it != idx }.forEach { addColumn(tableModel.getColumnName(it)) }
            (0 until tableModel.rowCount).forEach { r ->
                addRow((0 until tableModel.columnCount).filter { it != idx }
                    .map { tableModel.getValueAt(r, it) ?: "" }.toTypedArray())
            }
        }
        table.model = tableModel
        tableModel.addTableModelListener { scheduleDocumentUpdate() }
        scheduleDocumentUpdate()
    }
    
    private fun removeRow(idx: Int) {
        if (idx in 0 until tableModel.rowCount) {
            tableModel.removeRow(idx)
            scheduleDocumentUpdate()
        }
    }
    
    fun loadTableData(data: ExamplesTableParser.TableData, examplesElement: PsiElement) {
        currentExamplesElement = examplesElement
        
        tableModel = DefaultTableModel().apply {
            data.headers.forEach { addColumn(it) }
            data.rows.forEach { row ->
                addRow((row + List(maxOf(0, data.headers.size - row.size)) { "" }).toTypedArray())
            }
            
            if (rowCount == 0 && columnCount > 0) addRow(Array(columnCount) { "" })
            if (columnCount == 0) {
                addColumn("column1")
                addRow(arrayOf(""))
            }
        }
        
        table.model = tableModel
        tableModel.addTableModelListener {
            scheduleDocumentUpdate()
            updateRowHeader()
        }
        updateRowHeader()
    }
    
    private fun scheduleDocumentUpdate() {
        updateTimer?.stop()
        updateTimer = Timer(300) { updateDocument() }.apply {
            isRepeats = false
            start()
        }
    }
    
    private fun updateDocument() {
        currentExamplesElement?.let { elem ->
            val headers = (0 until tableModel.columnCount).map { tableModel.getColumnName(it) }
            val rows = (0 until tableModel.rowCount).map { r ->
                (0 until tableModel.columnCount).map { c -> tableModel.getValueAt(r, c)?.toString() ?: "" }
            }
            ExamplesTableFormatter.updateTableInDocument(elem, headers, rows)
        }
    }
}