package dev.classgraph.editor

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual files `caldograph://<diagram id>` that stand for diagrams, so diagrams open as editor tabs.
 * Being a registered file system lets the IDE reopen those tabs (and their splits) when the project is reopened.
 * The project a diagram belongs to is checked by [DiagramEditorProvider.accept]; diagram ids are UUIDs.
 */
class DiagramFileSystem : DeprecatedVirtualFileSystem() {

    private val files = ConcurrentHashMap<String, DiagramVirtualFile>()

    override fun getProtocol() = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? =
        path.takeIf { it.isNotBlank() && '/' !in it }?.let(::file)

    /** One instance per diagram, so every tab and lookup refers to the same file. */
    fun file(diagramId: String): DiagramVirtualFile = files.computeIfAbsent(diagramId) { DiagramVirtualFile(it, this) }

    /** Called when a diagram is deleted; a later diagram never reuses the id. */
    fun forget(diagramId: String) {
        files.remove(diagramId)?.valid = false
    }

    override fun refresh(asynchronous: Boolean) = Unit

    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

    companion object {
        const val PROTOCOL = "caldograph"

        fun getInstance(): DiagramFileSystem = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as DiagramFileSystem
    }
}
