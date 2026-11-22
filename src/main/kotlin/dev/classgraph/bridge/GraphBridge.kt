package dev.classgraph.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * JS ↔ Kotlin channel. Installs `window.__classGraphBridge.send(json)` after each page load and delivers
 * Kotlin messages through `window.__classGraphReceive(message)`.
 */
class GraphBridge(
    private val browser: JBCefBrowser,
    parent: Disposable,
    private val onMessage: (Inbound) -> Unit,
) {
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    init {
        Disposer.register(parent, query)
        query.addHandler { json ->
            try {
                onMessage(decodeInbound(json))
            } catch (e: Exception) {
                LOG.warn("Invalid message from web UI: $json", e)
            }
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                val script = """
                    window.__classGraphBridge = { send: function(json) { ${query.inject("json")} } };
                    window.dispatchEvent(new Event('classgraph-bridge-ready'));
                """.trimIndent()
                cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)
    }

    fun send(message: Outbound) {
        // JSON is a valid JS expression, so it can be passed as an object literal.
        val script = "window.__classGraphReceive && window.__classGraphReceive(${encodeOutbound(message)});"
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private companion object {
        val LOG = logger<GraphBridge>()
    }
}
