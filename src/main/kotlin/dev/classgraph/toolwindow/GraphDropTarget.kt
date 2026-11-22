package dev.classgraph.toolwindow

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDTarget
import com.intellij.ide.dnd.TransferableWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import java.awt.Component
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.io.File

/** What was dropped, extracted on the EDT before any PSI work happens. */
sealed interface DropPayload {
    data class Elements(val elements: List<PsiElement>) : DropPayload
    data class Files(val files: List<VirtualFile>) : DropPayload
    data class Text(val text: String) : DropPayload
}

/**
 * Swing drop target for the browser component. JCEF itself never sees IDE-internal drags (Project / Structure view),
 * so drops are intercepted here and forwarded with their position relative to [coordinateSpace].
 */
class GraphDropTarget(
    private val coordinateSpace: Component,
    private val onDrop: (payload: DropPayload, point: Point) -> Unit,
) : DnDTarget {

    override fun update(event: DnDEvent): Boolean {
        event.setDropPossible(extract(event) != null, "Add to Caldo Graph")
        return false
    }

    override fun drop(event: DnDEvent) {
        val payload = extract(event) ?: return
        onDrop(payload, event.getPointOn(coordinateSpace))
    }

    private fun extract(event: DnDEvent): DropPayload? {
        val wrapper = event.attachedObject as? TransferableWrapper
        if (wrapper != null) {
            wrapper.psiElements?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { return DropPayload.Elements(it) }
            wrapper.asFileList()?.let(::toVirtualFiles)?.takeIf { it.isNotEmpty() }?.let { return DropPayload.Files(it) }
        }
        if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = runCatching { event.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull()
            files?.let(::toVirtualFiles)?.takeIf { it.isNotEmpty() }?.let { return DropPayload.Files(it) }
        }
        if (event.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = runCatching { event.getTransferData(DataFlavor.stringFlavor) as String }.getOrNull()
            text?.takeIf { it.isNotBlank() }?.let { return DropPayload.Text(it) }
        }
        return null
    }

    private fun toVirtualFiles(files: List<File>): List<VirtualFile> =
        files.mapNotNull { LocalFileSystem.getInstance().findFileByIoFile(it) }
}
