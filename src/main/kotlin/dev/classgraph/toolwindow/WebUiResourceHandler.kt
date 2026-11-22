package dev.classgraph.toolwindow

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI

/** Serves the bundled web app (`resources/webui`) under [ORIGIN], so the page never touches the network. */
object WebUiResourceHandler : CefRequestHandlerAdapter() {
    const val ORIGIN = "https://classgraph.local"
    const val INDEX_URL = "$ORIGIN/index.html"
    private const val RESOURCE_ROOT = "/webui"

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler? {
        if (!request.url.startsWith("$ORIGIN/")) return null
        return object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler =
                ClasspathResource(URI(request.url).path.ifEmpty { "/index.html" })
        }
    }

    private class ClasspathResource(path: String) : CefResourceHandlerAdapter() {
        private val bytes: ByteArray? = path.takeUnless { ".." in it }
            ?.let { WebUiResourceHandler::class.java.getResourceAsStream(RESOURCE_ROOT + it) }
            ?.use { it.readBytes() }
        private val mimeType = mimeTypeOf(path)
        private var offset = 0

        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
            callback.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
            if (bytes == null) {
                response.status = 404
                responseLength.set(0)
                return
            }
            response.status = 200
            response.mimeType = mimeType
            response.setHeaderByName("Cache-Control", "no-store", true)
            responseLength.set(bytes.size)
        }

        override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback?): Boolean {
            val data = bytes ?: return false
            val count = minOf(bytesToRead, data.size - offset)
            if (count <= 0) {
                bytesRead.set(0)
                return false
            }
            System.arraycopy(data, offset, dataOut, 0, count)
            offset += count
            bytesRead.set(count)
            return true
        }
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "json" -> "application/json"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}
