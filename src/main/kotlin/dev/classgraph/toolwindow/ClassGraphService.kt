package dev.classgraph.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.classgraph.editor.DiagramEditor
import dev.classgraph.editor.DiagramFileSystem
import dev.classgraph.editor.DiagramVirtualFile
import dev.classgraph.state.GraphStateService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the project's diagrams: creating, opening (as editor tabs), renaming and deleting them, and knowing which
 * open diagram actions such as "Add to Caldo Graph" should go to (the one selected last). Used on the EDT.
 */
@Service(Service.Level.PROJECT)
class ClassGraphService(private val project: Project) : Disposable {

    private val stateService get() = GraphStateService.getInstance(project)

    /** Open diagram tabs, oldest first. */
    private val editors = mutableListOf<DiagramEditor>()
    private var lastSelected: DiagramEditor? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                (event.newEditor as? DiagramEditor)?.let { lastSelected = it }
            }
        })
    }

    /** [listener] runs whenever diagrams are created, renamed or deleted, until [parent] is disposed. */
    fun addDiagramsListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * Runs [block] with the controller of the diagram selected last (opening a diagram, or creating one if there are
     * none, when no diagram tab is open), and brings that tab to the front.
     */
    fun withController(block: (GraphController) -> Unit) {
        val target = lastSelected?.takeIf { it in editors } ?: editors.lastOrNull()
        val editor = if (target != null) {
            FileEditorManager.getInstance(project).openFile(target.file, false, true)
            target
        } else {
            val diagram = stateService.diagrams.firstOrNull() ?: stateService.create().also { fireChanged() }
            open(diagram.id!!, focus = false)
        }
        editor?.panel?.controller?.let(block)
    }

    fun create(): DiagramEditor? {
        val diagram = stateService.create()
        fireChanged()
        return open(diagram.id!!, focus = true)
    }

    /** Opens the diagram's tab (or selects it if it is already open). */
    fun open(diagramId: String, focus: Boolean = true): DiagramEditor? {
        val file = file(diagramId) ?: return null
        return FileEditorManager.getInstance(project).openFile(file, focus, true).filterIsInstance<DiagramEditor>().firstOrNull()
    }

    fun rename(diagramId: String, name: String) {
        stateService.rename(diagramId, name)
        DiagramFileSystem.getInstance().file(diagramId).let {
            it.title = name
            FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(it)
        }
        fireChanged()
    }

    fun delete(diagramId: String) {
        FileEditorManager.getInstance(project).closeFile(DiagramFileSystem.getInstance().file(diagramId))
        stateService.delete(diagramId)
        DiagramFileSystem.getInstance().forget(diagramId)
        fireChanged()
    }

    fun isOpen(diagramId: String) = editors.any { it.diagramId == diagramId }

    /** Re-reads every open diagram, e.g. after the classification rules changed. */
    fun reanalyzeAll() = editors.forEach { it.panel.controller?.reanalyzeAll() }

    /**
     * Each tab has its own browser and controller, so a diagram is shown in one tab at a time: when a diagram is opened
     * in a second split (e.g. "Split Right"), the older tab is closed, which turns the split into a move.
     */
    fun editorCreated(editor: DiagramEditor) {
        val older = editors.filter { it.diagramId == editor.diagramId }
        editors += editor
        Disposer.register(editor) {
            editors -= editor
            if (lastSelected === editor) lastSelected = null
            fireChanged()
        }
        if (older.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater({ older.forEach(::closeTab) }, project.disposed)
        }
        fireChanged()
    }

    private fun closeTab(editor: DiagramEditor) {
        if (editor !in editors) return
        val window = FileEditorManagerEx.getInstanceEx(project).windows.firstOrNull { window ->
            window.getComposite(editor.file)?.allEditors?.any { it === editor } == true
        }
        window?.closeFile(editor.file)
    }

    private fun file(diagramId: String): DiagramVirtualFile? {
        val diagram = stateService.diagram(diagramId) ?: return null
        return DiagramFileSystem.getInstance().file(diagramId).also { it.title = diagram.name ?: it.title }
    }

    /** Also called when a diagram's contents change, so the diagram list can show its size. */
    fun fireChanged() = listeners.forEach { it() }

    override fun dispose() = Unit

    companion object {
        const val TOOL_WINDOW_ID = "Caldo Graph"

        fun getInstance(project: Project): ClassGraphService = project.getService(ClassGraphService::class.java)
    }
}
