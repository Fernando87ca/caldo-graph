package dev.classgraph.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import dev.classgraph.state.GraphStateService
import dev.classgraph.toolwindow.ClassGraphPanel
import dev.classgraph.toolwindow.ClassGraphService
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** One diagram tab: a [ClassGraphPanel] (browser + controller) for the diagram of [file]. */
class DiagramEditor(project: Project, private val file: DiagramVirtualFile) : UserDataHolderBase(), FileEditor {

    val diagramId get() = file.diagramId
    val panel = ClassGraphPanel(project, file.diagramId).also { Disposer.register(this, it) }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent? = panel.browserComponent
    override fun getName() = "Caldo Graph"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}

class DiagramEditorProvider : FileEditorProvider, DumbAware {

    /** Only diagrams of this project; a tab restored for a deleted diagram is dropped. */
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file !is DiagramVirtualFile) return false
        val diagram = GraphStateService.getInstance(project).diagram(file.diagramId) ?: return false
        file.title = diagram.name ?: file.title
        return true
    }

    override fun acceptRequiresReadAction() = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val editor = DiagramEditor(project, file as DiagramVirtualFile)
        ClassGraphService.getInstance(project).editorCreated(editor)
        return editor
    }

    override fun getEditorTypeId() = "caldo-graph-diagram"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class DiagramTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? DiagramVirtualFile)?.let { GraphStateService.getInstance(project).diagram(it.diagramId)?.name }
}
