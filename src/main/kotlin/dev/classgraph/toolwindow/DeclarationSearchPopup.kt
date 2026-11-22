package dev.classgraph.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.classgraph.analysis.DeclarationSearch
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Callable
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Text field plus result list to find a class, composable or Kotlin file by name and hand it to [onChosen].
 * Searching runs in non-blocking read actions; each keystroke cancels the previous search.
 */
class DeclarationSearchPopup(private val project: Project, private val onChosen: (SmartPsiElementPointer<PsiElement>) -> Unit) {

    private class Item(val result: DeclarationSearch.Result, val pointer: SmartPsiElementPointer<PsiElement>)

    private val search = DeclarationSearch(project)
    private val field = SearchTextField(false)
    private val model = DefaultListModel<Item>()
    private val list = JBList(model).apply {
        cellRenderer = object : ColoredListCellRenderer<Item>() {
            override fun customizeCellRenderer(list: JList<out Item>, value: Item, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = when (value.result.kind) {
                    DeclarationSearch.Kind.CLASS -> AllIcons.Nodes.Class
                    DeclarationSearch.Kind.INTERFACE -> AllIcons.Nodes.Interface
                    DeclarationSearch.Kind.FUNCTION -> AllIcons.Nodes.Function
                    DeclarationSearch.Kind.FILE -> AllIcons.FileTypes.Any_type
                }
                append(value.result.name)
                append("  ${value.result.location}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        emptyText.text = "Type a class, composable or file name"
        visibleRowCount = 12
    }
    private var names: DeclarationSearch.Names? = null
    private var popup: JBPopup? = null

    fun show(at: RelativePoint?) {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(6)
            add(field, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
            preferredSize = JBUI.size(520, 320)
        }
        wireEvents()
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, field.textEditor)
            .setTitle("Add to Caldo Graph")
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        this.popup = popup
        if (at != null) popup.show(at) else popup.showInFocusCenter()

        ReadAction.nonBlocking(Callable { search.names() })
            .inSmartMode(project)
            .expireWhen { popup.isDisposed }
            .finishOnUiThread(ModalityState.any()) { names = it; refresh() }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun wireEvents() {
        field.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        field.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> move(1).also { e.consume() }
                    KeyEvent.VK_UP -> move(-1).also { e.consume() }
                    KeyEvent.VK_ENTER -> choose().also { e.consume() }
                }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 1) choose()
            }
        })
    }

    private fun move(delta: Int) {
        if (model.isEmpty) return
        val index = (list.selectedIndex + delta).coerceIn(0, model.size() - 1)
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    private fun choose() {
        val item = list.selectedValue ?: return
        popup?.cancel()
        onChosen(item.pointer)
    }

    private fun refresh() {
        val names = names ?: return
        val pattern = field.text
        ReadAction.nonBlocking(Callable {
            search.find(pattern, names).map { Item(it, SmartPointerManager.createPointer(it.element)) }
        })
            .coalesceBy(this)
            .expireWhen { popup?.isDisposed != false }
            .finishOnUiThread(ModalityState.any()) { items ->
                model.clear()
                items.forEach(model::addElement)
                if (!model.isEmpty) list.selectedIndex = 0
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
