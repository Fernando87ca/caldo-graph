package dev.classgraph.toolwindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/** Gives actions access to the controller of the project's Caldo Graph tool window. */
@Service(Service.Level.PROJECT)
class ClassGraphService(private val project: Project) {
    var controller: GraphController? = null

    /** Opens the tool window (creating its content if needed) and runs [block] with its controller. */
    fun withController(block: (GraphController) -> Unit) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate { controller?.let(block) }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Caldo Graph"

        fun getInstance(project: Project): ClassGraphService = project.getService(ClassGraphService::class.java)
    }
}
