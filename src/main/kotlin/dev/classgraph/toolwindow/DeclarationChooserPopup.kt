package dev.classgraph.toolwindow

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Filterable checklist: typing filters by name (camel-hump, case-insensitive), clicking a row toggles it, and nothing
 * is added until "Add" (or Enter, which also takes the highlighted row when nothing is checked). "Cancel" adds nothing.
 */
class DeclarationChooserPopup<T>(
    private val title: String,
    private val items: List<T>,
    private val name: (T) -> String,
    private val detail: (T) -> String,
    private val onChosen: (List<T>) -> Unit,
) {
    private val checked = LinkedHashSet<T>()
    private val field = SearchTextField(false)
    private val model = DefaultListModel<T>()
    private val list = JBList(model)
    private val addButton = JButton("Add")
    private val cancelButton = JButton("Cancel")
    private var popup: JBPopup? = null

    fun show(component: JComponent) {
        list.cellRenderer = Renderer()
        list.visibleRowCount = 12
        list.emptyText.text = "No matching declarations"
        wireEvents()
        filter()

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            add(cancelButton)
            add(addButton)
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(6)
            add(field, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, field.textEditor)
            .setTitle(title)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
            .also { it.showInCenterOf(component) }
    }

    private fun wireEvents() {
        field.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
        })
        field.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> move(1)
                    KeyEvent.VK_UP -> move(-1)
                    KeyEvent.VK_ENTER -> accept(includeHighlighted = true)
                    else -> return
                }
                e.consume()
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) toggle(model.getElementAt(index))
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_SPACE -> list.selectedValue?.let(::toggle)
                    KeyEvent.VK_ENTER -> accept(includeHighlighted = true)
                    else -> return
                }
                e.consume()
            }
        })
        addButton.addActionListener { accept(includeHighlighted = false) }
        cancelButton.addActionListener { popup?.cancel() }
    }

    private fun filter() {
        model.clear()
        matching(items, name, field.text).forEach(model::addElement)
        if (!model.isEmpty) list.selectedIndex = 0
        updateButtons()
    }

    private fun move(delta: Int) {
        if (model.isEmpty) return
        val index = (list.selectedIndex + delta).coerceIn(0, model.size() - 1)
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    private fun toggle(item: T) {
        if (!checked.add(item)) checked.remove(item)
        list.repaint()
        updateButtons()
    }

    private fun updateButtons() {
        addButton.isEnabled = checked.isNotEmpty()
        addButton.text = if (checked.size > 1) "Add ${checked.size}" else "Add"
    }

    private fun accept(includeHighlighted: Boolean) {
        val chosen = checked.toList().ifEmpty { if (includeHighlighted) listOfNotNull(list.selectedValue) else emptyList() }
        if (chosen.isEmpty()) return
        popup?.cancel()
        onChosen(chosen)
    }

    companion object {
        /** Items whose name matches [text] like "Go to Class" does (case-insensitive, camel humps); all when blank. */
        fun <T> matching(items: List<T>, name: (T) -> String, text: String): List<T> {
            val pattern = text.trim()
            if (pattern.isEmpty()) return items
            val matcher = NameUtil.buildMatcher("*$pattern").build()
            return items.filter { matcher.matches(name(it)) }
        }
    }

    private inner class Renderer : ListCellRenderer<T> {
        private val checkBox = JBCheckBox()
        private val label = JBLabel()
        private val row = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(1, 4)
            add(checkBox, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(list: JList<out T>, value: T, index: Int, selected: Boolean, focus: Boolean): Component {
            val background = UIUtil.getListBackground(selected, list.hasFocus() || selected)
            val foreground = UIUtil.getListForeground(selected, true)
            row.background = background
            checkBox.background = background
            checkBox.isSelected = value in checked
            val detailColor = if (selected) foreground else SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            label.text = "<html>${escape(name(value))}&nbsp;&nbsp;<font color='#${hex(detailColor)}'>${escape(detail(value))}</font></html>"
            label.foreground = foreground
            return row
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun hex(color: java.awt.Color) = "%02x%02x%02x".format(color.red, color.green, color.blue)
    }
}
