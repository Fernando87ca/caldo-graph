package dev.classgraph.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import java.util.UUID

/** The project's diagrams (contents and layout), stored in the workspace file (not shared through VCS). */
@Service(Service.Level.PROJECT)
@State(name = "ClassGraph", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GraphStateService : SimplePersistentStateComponent<GraphState>(GraphState()) {

    override fun loadState(state: GraphState) {
        super.loadState(state)
        migrateSingleGraph(state)
    }

    val diagrams: List<DiagramState> get() = state.diagrams.toList()

    fun diagram(id: String): DiagramState? = state.diagrams.firstOrNull { it.id == id }

    fun create(name: String = nextName()): DiagramState {
        val diagram = DiagramState().apply {
            id = UUID.randomUUID().toString()
            this.name = name
        }
        state.diagrams.add(diagram)
        changed()
        return diagram
    }

    fun rename(id: String, name: String) {
        diagram(id)?.name = name
        changed()
    }

    fun delete(id: String) {
        state.diagrams.removeIf { it.id == id }
        changed()
    }

    /** Runs [edit] on the diagram (if it still exists) and marks the state as modified, so nested edits are saved. */
    fun update(id: String, edit: DiagramState.() -> Unit) {
        diagram(id)?.edit()
        changed()
    }

    private fun nextName(): String {
        val names = state.diagrams.mapNotNull { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "Diagram $it" }.first { it !in names }
    }

    private fun changed() = state.intIncrementModificationCount()

    /** Before diagrams existed the graph was stored at the top level; it becomes the first diagram. */
    private fun migrateSingleGraph(state: GraphState) {
        if (state.nodes.isEmpty() || state.diagrams.isNotEmpty()) return
        state.diagrams.add(DiagramState().apply {
            id = UUID.randomUUID().toString()
            name = "Diagram 1"
            nodes = state.nodes.toMutableList()
            legendVisible = state.legendVisible
            hiddenRelationKinds = state.hiddenRelationKinds.toMutableList()
            hiddenNodeKinds = state.hiddenNodeKinds.toMutableList()
        })
        state.nodes = mutableListOf()
        changed()
    }

    companion object {
        fun getInstance(project: Project): GraphStateService = project.getService(GraphStateService::class.java)
    }
}

class GraphState : BaseState() {
    var diagrams by list<DiagramState>()

    // Single-graph format from before diagrams existed; only read to migrate it.
    var nodes by list<NodeState>()
    var legendVisible by property(true)
    var hiddenRelationKinds by list<String>()
    var hiddenNodeKinds by list<String>()
}

class DiagramState : BaseState() {
    var id by string()
    var name by string()
    var nodes by list<NodeState>()
    var legendVisible by property(true)
    var hiddenRelationKinds by list<String>()
    var hiddenNodeKinds by list<String>()
}

/** Name, package and kind are kept so a node whose declaration disappeared can still be drawn as "not found". */
class NodeState : BaseState() {
    var id by string()
    var name by string()
    var packageName by string()
    var kind by string()
    var x by property(0f)
    var y by property(0f)
    var collapsed by list<String>()
    var parent by string()
}
