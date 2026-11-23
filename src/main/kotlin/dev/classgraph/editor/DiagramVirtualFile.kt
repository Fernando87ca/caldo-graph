package dev.classgraph.editor

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** A diagram opened as an editor tab. It has no content of its own: the diagram lives in `GraphStateService`. */
class DiagramVirtualFile(val diagramId: String, private val fileSystem: DiagramFileSystem) : VirtualFile() {

    /** Diagram name shown in the tab; kept up to date by `ClassGraphService`. */
    @Volatile
    var title: String = "Caldo Graph"

    @Volatile
    var valid = true

    override fun getName() = diagramId
    override fun getPresentableName() = title
    override fun getFileSystem(): VirtualFileSystem = fileSystem
    override fun getPath() = diagramId
    override fun getFileType(): FileType = DiagramFileType
    override fun isWritable() = false
    override fun isDirectory() = false
    override fun isValid() = valid
    override fun getParent(): VirtualFile? = null
    override fun getChildren(): Array<VirtualFile> = EMPTY_ARRAY
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
        throw UnsupportedOperationException("Caldo Graph diagrams are not written as files")
    override fun contentsToByteArray() = ByteArray(0)
    override fun getTimeStamp() = 0L
    override fun getModificationStamp() = 0L
    override fun getLength() = 0L
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) = Unit
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun equals(other: Any?) = other is DiagramVirtualFile && other.diagramId == diagramId
    override fun hashCode() = diagramId.hashCode()
}

object DiagramFileType : FileType {
    private val diagramIcon by lazy { IconLoader.getIcon("/icons/classGraph.svg", DiagramFileType::class.java) }

    override fun getName() = "Caldo Graph Diagram"
    override fun getDescription() = "Caldo Graph diagram"
    override fun getDefaultExtension() = ""
    override fun getIcon() = diagramIcon
    override fun isBinary() = true
    override fun isReadOnly() = true
}
