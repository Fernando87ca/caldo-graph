package dev.classgraph.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import dev.classgraph.state.GraphStateService
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * The Caldo Graph tool window: the project's diagrams. Diagrams open as editor tabs, so they can be split,
 * moved and closed like any other editor tab.
 */
class DiagramListPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class Item(val id: String, val name: String, val nodeCount: Int)

    private val service get() = ClassGraphService.getInstance(project)
    private val model = CollectionListModel<Item>()
    private val list = JBList(model)

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "No diagrams yet"
        list.emptyText.appendSecondaryText("Create a diagram", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { service.create() }
        list.cellRenderer = object : ColoredListCellRenderer<Item>() {
            override fun customizeCellRenderer(list: JList<out Item>, value: Item, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = AllIcons.FileTypes.Diagram
                append(value.name, if (service.isOpen(value.id)) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.nodeCount} " + if (value.nodeCount == 1) "box" else "boxes", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                selected()?.let { service.open(it.id) }
                return true
            }
        }.installOn(list)
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> selected()?.let { service.open(it.id) }
                    KeyEvent.VK_F2 -> rename()
                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> delete()
                    else -> return
                }
                e.consume()
            }
        })

        setContent(ScrollPaneFactory.createScrollPane(list, true))
        toolbar = createToolbar()
        service.addDiagramsListener(this, ::reload)
        reload()
    }

    private fun selected(): Item? = list.selectedValue

    private fun reload() {
        val selectedId = selected()?.id
        model.replaceAll(GraphStateService.getInstance(project).diagrams.map { Item(it.id!!, it.name ?: "Diagram", it.nodes.size) })
        model.items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
    }

    private fun rename() {
        val item = selected() ?: return
        val name = Messages.showInputDialog(project, "Diagram name:", "Rename Diagram", null, item.name, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        service.rename(item.id, name)
    }

    private fun delete() {
        val item = selected() ?: return
        val answer = Messages.showYesNoDialog(project, "Delete the diagram \"${item.name}\"? This cannot be undone.", "Delete Diagram", Messages.getQuestionIcon())
        if (answer == Messages.YES) service.delete(item.id)
    }

    private fun createToolbar() = ActionManager.getInstance().createActionToolbar(
        "ClassGraphDiagramsToolbar",
        DefaultActionGroup(
            object : DumbAwareAction("New Diagram", "Create a diagram and open it in a new tab", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    service.create()
                }
            },
            selectionAction("Open", "Open the diagram in a tab", AllIcons.Actions.MenuOpen) { service.open(it.id) },
            selectionAction("Rename…", "Rename the diagram", AllIcons.Actions.Edit) { rename() },
            selectionAction("Delete", "Delete the diagram", AllIcons.General.Remove) { delete() },
        ),
        true,
    ).also { it.targetComponent = this }.component

    private fun selectionAction(text: String, description: String, icon: javax.swing.Icon, run: (Item) -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selected() != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let(run)
            }
        }

    override fun dispose() = Unit
}
