package dev.classgraph.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/** Graph contents and layout per project, stored in the workspace file (not shared through VCS). */
@Service(Service.Level.PROJECT)
@State(name = "ClassGraph", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GraphStateService : SimplePersistentStateComponent<GraphState>(GraphState()) {
    companion object {
        fun getInstance(project: Project): GraphStateService = project.getService(GraphStateService::class.java)
    }
}

class GraphState : BaseState() {
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
