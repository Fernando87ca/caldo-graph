package dev.classgraph.toolwindow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64

/** Saves an image produced by the web app (`data:` URL) where the user chooses. Runs on the EDT. */
object ImageExport {
    fun save(project: Project, format: String, dataUrl: String) {
        val extension = if (format == "svg") "svg" else "png"
        val bytes = decode(dataUrl) ?: return notify(project, "Could not read the exported image.", NotificationType.ERROR)
        val descriptor = FileSaverDescriptor("Export Caldo Graph", "Save the graph as ${extension.uppercase()}", extension)
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("class-graph.$extension") ?: return
        try {
            target.file.writeBytes(bytes)
            target.virtualFile?.refresh(true, false)
            notify(project, "Exported to ${target.file.path}", NotificationType.INFORMATION)
        } catch (e: IOException) {
            notify(project, "Export failed: ${e.message}", NotificationType.ERROR)
        }
    }

    /** Supports `data:<mime>;base64,<data>` and percent-encoded `data:<mime>;charset=utf-8,<data>`. */
    internal fun decode(dataUrl: String): ByteArray? {
        if (!dataUrl.startsWith("data:")) return null
        val header = dataUrl.substringBefore(',')
        val data = dataUrl.substringAfter(',', "")
        return runCatching {
            if (header.endsWith(";base64")) Base64.getDecoder().decode(data)
            else URLDecoder.decode(data.replace("+", "%2B"), Charsets.UTF_8).toByteArray(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Caldo Graph").createNotification(message, type).notify(project)
    }
}
