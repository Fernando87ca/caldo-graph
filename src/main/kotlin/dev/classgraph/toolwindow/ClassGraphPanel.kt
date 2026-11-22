package dev.classgraph.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.DnDManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import dev.classgraph.bridge.GraphBridge
import dev.classgraph.bridge.Inbound
import dev.classgraph.bridge.Outbound
import dev.classgraph.bridge.Point
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import com.intellij.ui.awt.RelativePoint
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingConstants

class ClassGraphPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private var bridge: GraphBridge? = null
    private var browserComponent: JComponent? = null
    private var controller: GraphController? = null

    /** Messages produced before the web app reported `ready`; only touched on the EDT. */
    private val pending = mutableListOf<Outbound>()
    private var webReady = false

    init {
        if (JBCefApp.isSupported()) {
            createBrowser()
        } else {
            setContent(JBLabel("Caldo Graph needs JCEF (install the \"Web Browser (JCEF)\" plugin).", SwingConstants.CENTER))
        }
    }

    private fun createBrowser() {
        // Off-screen rendering makes the browser a lightweight Swing component, so Swing drop targets work on it.
        val browser = JBCefBrowser.createBuilder()
            .setOffScreenRendering(true)
            .setEnableOpenDevToolsMenuItem(true)
            .build()
        Disposer.register(this, browser)
        browserComponent = browser.component
        browser.jbCefClient.addRequestHandler(WebUiResourceHandler, browser.cefBrowser)
        bridge = GraphBridge(browser, this) { message -> UIUtil.invokeLaterIfNeeded { handle(message) } }

        val controller = GraphController(project, browser.component, this, ::send)
        this.controller = controller
        ClassGraphService.getInstance(project).controller = controller

        registerDropTarget(GraphDropTarget(browser.component, ::onDrop), browser.component)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { send(ThemeColors.current()) })

        setContent(browser.component)
        toolbar = createToolbar(browser)
        registerDeleteShortcut()
        browser.loadURL(WebUiResourceHandler.INDEX_URL)
    }

    /** Delete/Backspace on the canvas remove the selected boxes (or the hovered one); handled here so the IDE does not keep the key. */
    private fun registerDeleteShortcut() {
        val delete = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                controller?.command("deleteSelection")
            }
        }
        val keys = CustomShortcutSet(
            KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), null),
            KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), null),
        )
        delete.registerCustomShortcutSet(keys, this, this)
    }

    /** Registers on the whole component tree, since the drop is dispatched to the deepest component under the cursor. */
    private fun registerDropTarget(target: GraphDropTarget, component: Component) {
        if (component is JComponent) DnDManager.getInstance().registerTarget(target, component, this)
        if (component is Container) component.components.forEach { registerDropTarget(target, it) }
    }

    private fun createToolbar(browser: JBCefBrowser): JComponent {
        val group = DefaultActionGroup(
            object : DumbAwareAction("Add Class or Composable…", "Search the project by name and add the declaration", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = openSearch(null)
            },
            object : DumbAwareAction("Clear Graph", "Remove all nodes", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    controller?.clear()
                }
            },
            object : DumbAwareAction("Auto-Layout", "Arrange the graph automatically", AllIcons.Graph.Layout) {
                override fun actionPerformed(e: AnActionEvent) {
                    controller?.command("autoLayout")
                }
            },
            object : DumbAwareAction("Zoom to Fit", null, AllIcons.General.FitContent) {
                override fun actionPerformed(e: AnActionEvent) {
                    controller?.command("fitView")
                }
            },
            DefaultActionGroup("Export", true).apply {
                templatePresentation.icon = AllIcons.ToolbarDecorator.Export
                add(object : DumbAwareAction("Export as PNG…") {
                    override fun actionPerformed(e: AnActionEvent) {
                        controller?.command("exportPng")
                    }
                })
                add(object : DumbAwareAction("Export as SVG…") {
                    override fun actionPerformed(e: AnActionEvent) {
                        controller?.command("exportSvg")
                    }
                })
            },
            object : DumbAwareAction("Reload Web UI", null, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    webReady = false
                    browser.cefBrowser.reloadIgnoreCache()
                }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("ClassGraphToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    /** Search popup; with a [point] (browser client coordinates) it opens there and the result is placed there. */
    private fun openSearch(point: Point?) {
        val component = browserComponent ?: return
        val at = point?.let { RelativePoint(component, java.awt.Point(it.x.toInt(), it.y.toInt())) }
        DeclarationSearchPopup(project) { pointer ->
            controller?.add(anchor = point) { resolver -> resolver.fromElements(listOfNotNull(pointer.element)) }
        }.show(at ?: RelativePoint.getCenterOf(component))
    }

    private fun handle(message: Inbound) {
        when (message) {
            Inbound.Ready -> {
                webReady = true
                bridge?.send(ThemeColors.current())
                pending.forEach { bridge?.send(it) }
                pending.clear()
                controller?.restore()
            }
            is Inbound.Ping -> send(Outbound.Pong(message.nonce, ApplicationInfo.getInstance().let { "${it.fullApplicationName} (${it.build})" }))
            is Inbound.Log -> LOG.info("[webui:${message.level}] ${message.message}")
            is Inbound.Navigate -> controller?.navigate(message)
            is Inbound.GraphChanged -> controller?.onGraphChanged(message)
            is Inbound.Expand -> controller?.expand(message)
            is Inbound.FindIncoming -> controller?.findIncoming(message.id)
            is Inbound.ExportImage -> ImageExport.save(project, message.format, message.dataUrl)
            is Inbound.OpenSearch -> openSearch(Point(message.x, message.y))
        }
    }

    private fun send(message: Outbound) {
        UIUtil.invokeLaterIfNeeded {
            if (webReady) bridge?.send(message) else pending += message
        }
    }

    private fun onDrop(payload: DropPayload, point: java.awt.Point) {
        controller?.add(Point(point.x.toDouble(), point.y.toDouble())) { resolver ->
            when (payload) {
                is DropPayload.Elements -> resolver.fromElements(payload.elements.filter { it.isValid })
                is DropPayload.Files -> resolver.fromFiles(payload.files.filter { it.isValid })
                is DropPayload.Text -> resolver.fromText(payload.text)
            }
        }
    }

    override fun dispose() {
        val service = ClassGraphService.getInstance(project)
        if (service.controller === controller) service.controller = null
    }

    private companion object {
        val LOG = logger<ClassGraphPanel>()
    }
}
