package dev.classgraph.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.classgraph.analysis.DeclarationResolver
import dev.classgraph.analysis.IncomingRelationFinder
import dev.classgraph.analysis.NodeAnalyzer
import dev.classgraph.analysis.RelationExtractor
import dev.classgraph.analysis.Resolution
import dev.classgraph.bridge.Filters
import dev.classgraph.bridge.Inbound
import dev.classgraph.bridge.Near
import dev.classgraph.bridge.NodeRelations
import dev.classgraph.bridge.Outbound
import dev.classgraph.bridge.PlacedNode
import dev.classgraph.bridge.Point
import dev.classgraph.model.GraphNode
import dev.classgraph.model.NodeId
import dev.classgraph.model.NodeKind
import dev.classgraph.model.Relation
import dev.classgraph.state.DiagramState
import dev.classgraph.state.GraphStateService
import dev.classgraph.state.NodeState
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Alarm
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.concurrent.Callable
import javax.swing.JComponent

/**
 * Owns the contents of one open diagram (identified by [diagramId]): turns drops/actions into nodes, computes their relations,
 * restores and persists the graph, and navigates to code.
 * Public methods run on the EDT; PSI work happens in non-blocking read actions or a background task.
 */
class GraphController(
    private val project: Project,
    val diagramId: String,
    private val component: JComponent,
    private val parent: Disposable,
    private val send: (Outbound) -> Unit,
) {
    private val analyzer = NodeAnalyzer(project)
    private val extractor = RelationExtractor(project)
    private val incomingFinder = IncomingRelationFinder(project)
    private val stateService get() = GraphStateService.getInstance(project)

    /** Nodes currently shown, by id. */
    private val nodes = LinkedHashMap<String, GraphNode>()

    /** Additions requested before the saved graph was restored; restoring replaces the whole graph. */
    private var restored = false
    private val deferred = mutableListOf<() -> Unit>()

    /** Nodes waiting for their (slow) incoming-relations search, processed by one background task at a time. */
    private val incomingQueue = LinkedHashSet<String>()
    private var incomingTaskRunning = false

    @Volatile
    private var disposed = false

    /** Last known incoming relations per node (once its usage search finished), kept current by [refreshFiles]. */
    private val incoming = HashMap<String, List<Relation>>()

    init {
        Disposer.register(parent) { disposed = true }
        listenToCodeChanges()
    }

    private class Analyzed(val node: GraphNode, val outgoing: List<Relation>)

    fun restore() {
        val state = stateService.diagram(diagramId) ?: DiagramState()
        val saved = state.nodes.toList()
        readAction({
            saved.mapNotNull { s ->
                val id = s.id?.let(::NodeId) ?: return@mapNotNull null
                val analyzed = analyzer.locate(id)?.let(::analyzeWithRelations) ?: Analyzed(notFoundNode(id, s), emptyList())
                s to analyzed
            }
        }) { restoredNodes ->
            nodes.clear()
            restoredNodes.forEach { (_, a) -> nodes[a.node.id.value] = a.node }
            val placed = restoredNodes.map { (s, a) -> PlacedNode(a.node, Point(s.x.toDouble(), s.y.toDouble()), s.collapsed.toList(), s.parent) }
            send(Outbound.Graph(placed, state.legendVisible, Filters(state.hiddenRelationKinds.toList(), state.hiddenNodeKinds.toList())))
            sendOutgoing(restoredNodes.map { it.second })
            enqueueIncoming(restoredNodes.filterNot { it.second.node.notFound }.map { it.second.node.id.value })
            restored = true
            deferred.forEach { it() }
            deferred.clear()
        }
    }

    /** Resolves [resolve] off the EDT and adds the result; [anchor] is a browser client point (null = viewport center). */
    fun add(anchor: Point?, resolve: (DeclarationResolver) -> Resolution) {
        if (!restored) {
            deferred += { add(anchor, resolve) }
            return
        }
        val present = nodes.keys.toSet()
        readAction({
            val resolution = resolve(DeclarationResolver(project))
            Prepared(
                nodes = resolution.selected.mapNotNull(::analyzeWithRelations),
                optional = DeclarationResolver.offerable(resolution, present) { analyzer.nodeId(it)?.value }.map(::choice),
                ambiguous = resolution.ambiguous.map(::choice),
            )
        }) { prepared ->
            addNodes(prepared.nodes, anchor)
            if (prepared.optional.isNotEmpty()) {
                choose("Add From the Same File", prepared.optional) { addChoices(it, anchor) }
            }
            if (prepared.ambiguous.isNotEmpty()) {
                choose("Choose Declaration", prepared.ambiguous) { addChoices(it, anchor) }
            }
        }
    }

    fun expand(message: Inbound.Expand) {
        val near = Near(message.id, message.side)
        readAction({
            message.targets.mapNotNull { id -> analyzer.locate(NodeId(id))?.let(::analyzeWithRelations) }
        }) { addNodes(it, anchor = null, near = near, parentId = message.id) }
    }

    fun findIncoming(id: String) = enqueueIncoming(listOf(id))

    fun clear() {
        nodes.clear()
        incoming.clear()
        incomingQueue.clear()
        saveNodes(emptyList())
        val state = stateService.diagram(diagramId) ?: DiagramState()
        send(Outbound.Graph(emptyList(), state.legendVisible, Filters(state.hiddenRelationKinds.toList(), state.hiddenNodeKinds.toList())))
    }

    fun command(name: String) = send(Outbound.Command(name))

    /** Re-classifies and re-reads every node, e.g. after the classification rules changed. */
    fun reanalyzeAll() {
        val ids = nodes.keys.toList()
        readAction({ ids.map { id -> analyzer.locate(NodeId(id))?.let(::analyzeWithRelations) ?: missing(id) } }) { analyzed ->
            val current = analyzed.filter { it.node.id.value in nodes }
            current.forEach { nodes[it.node.id.value] = it.node }
            send(Outbound.NodesUpdated(current.map { it.node }))
            sendOutgoing(current)
        }
    }

    /**
     * Brings the graph up to date after edits in [paths]: nodes declared there are re-analyzed (or marked "not found"),
     * and since incoming relations are other declarations' outgoing ones, recomputing the outgoing relations of every
     * declaration in those files is enough to update the incoming lists without a new usage search.
     */
    fun refreshFiles(files: Set<VirtualFile>) {
        if (nodes.isEmpty()) return
        val paths = files.map { it.path }.toSet()
        val shown = nodes.toMap()
        val knownIncoming = incoming.toMap()
        readAction({
            val psiManager = PsiManager.getInstance(project)
            val fileDeclarations = files
                .filter { it.isValid }
                .mapNotNull { psiManager.findFile(it) as? KtFile }
                .flatMap(::graphableDeclarations)
            val fresh = fileDeclarations.mapNotNull { declaration ->
                analyzer.nodeId(declaration)?.let { it.value to declaration }
            }.toMap()
            val freshOutgoing = fresh.mapValues { (_, declaration) -> extractor.outgoing(declaration) }

            // Nodes declared in the edited files, plus missing ones that may have been moved or recreated.
            val affected = shown.values.filter { it.id.value in fresh || it.notFound || it.filePath() in paths }
            val updated = affected.map { node ->
                fresh[node.id.value]?.let { analyzer.analyze(it)?.let { n -> Analyzed(n, freshOutgoing.getValue(node.id.value)) } }
                    ?: analyzer.locate(node.id)?.let(::analyzeWithRelations)
                    ?: missing(node.id.value)
            }

            val newIncoming = knownIncoming.mapValues { (id, relations) ->
                val kept = relations.filter { it.source.value !in fresh && analyzer.locate(it.source) != null }
                kept + freshOutgoing.values.flatten().filter { it.target.value == id }
            }.filter { (id, relations) -> relations.toSet() != knownIncoming[id]?.toSet() }
            updated to newIncoming
        }) { (updated, newIncoming) ->
            val stillShown = updated.filter { it.node.id.value in nodes }
            stillShown.forEach { nodes[it.node.id.value] = it.node }
            if (stillShown.isNotEmpty()) send(Outbound.NodesUpdated(stillShown.map { it.node }))
            val incomingItems = newIncoming.filterKeys { it in nodes }.onEach { (id, r) -> incoming[id] = r }
            val items = stillShown.map { NodeRelations(it.node.id.value, outgoing = it.outgoing, incoming = incomingItems[it.node.id.value]) } +
                incomingItems.filterKeys { id -> stillShown.none { it.node.id.value == id } }.map { (id, r) -> NodeRelations(id, incoming = r) }
            if (items.isNotEmpty()) send(Outbound.Relations(items))
        }
    }

    private fun listenToCodeChanges() {
        val pending = LinkedHashSet<VirtualFile>()
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
        val listener = object : PsiTreeChangeAdapter() {
            private fun collect(event: PsiTreeChangeEvent) {
                val file = (event.file ?: event.child as? PsiFile ?: event.oldChild as? PsiFile) as? KtFile ?: return
                val virtualFile = file.virtualFile ?: return
                if (nodes.isEmpty()) return
                pending += virtualFile
                alarm.cancelAllRequests()
                alarm.addRequest({
                    val changed = pending.toSet()
                    pending.clear()
                    refreshFiles(changed)
                }, REFRESH_DELAY_MS)
            }

            override fun childrenChanged(event: PsiTreeChangeEvent) = collect(event)
            override fun childAdded(event: PsiTreeChangeEvent) = collect(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = collect(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = collect(event)
            override fun childMoved(event: PsiTreeChangeEvent) = collect(event)
            override fun propertyChanged(event: PsiTreeChangeEvent) = collect(event)
        }
        PsiManager.getInstance(project).addPsiTreeChangeListener(listener, parent)
    }

    private fun graphableDeclarations(file: KtFile): List<KtDeclaration> =
        PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java).filter { !it.isLocal } +
            file.declarations.filterIsInstance<KtNamedFunction>()

    private fun GraphNode.filePath(): String? = id.filePath ?: nodeFiles[id.value]

    private fun missing(id: String): Analyzed {
        val previous = nodes[id]
        val node = previous?.copy(notFound = true)
            ?: GraphNode(NodeId(id), NodeKind.OTHER, NodeId(id).fqName.substringAfterLast('.'), "", notFound = true)
        return Analyzed(node, emptyList())
    }

    fun onGraphChanged(message: Inbound.GraphChanged) {
        val ids = message.nodes.map { it.id }.toSet()
        nodes.keys.retainAll(ids)
        incomingQueue.retainAll(ids)
        incoming.keys.retainAll(ids)
        val saved = message.nodes.mapNotNull { layout ->
            val node = nodes[layout.id] ?: return@mapNotNull null
            NodeState().apply {
                id = layout.id
                name = node.name
                packageName = node.packageName
                kind = node.kind.name
                x = layout.x.toFloat()
                y = layout.y.toFloat()
                collapsed = layout.collapsed.toMutableList()
                parent = layout.parent
            }
        }
        stateService.update(diagramId) {
            nodes = saved.toMutableList()
            legendVisible = message.legendVisible
            hiddenRelationKinds = message.filters.hiddenRelationKinds.toMutableList()
            hiddenNodeKinds = message.filters.hiddenNodeKinds.toMutableList()
        }
        ClassGraphService.getInstance(project).fireChanged()
    }

    fun navigate(message: Inbound.Navigate) {
        readAction({
            val declaration = analyzer.locate(NodeId(message.id)) ?: return@readAction null
            val target = message.member?.let { findMember(declaration, it) } ?: declaration
            val file = target.containingFile?.virtualFile ?: return@readAction null
            OpenFileDescriptor(project, file, target.textOffset)
        }) { descriptor -> descriptor?.navigate(true) }
    }

    /** Files of class nodes (function ids already contain theirs), to notice when a class disappears from its file. */
    private val nodeFiles = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun analyzeWithRelations(declaration: KtDeclaration): Analyzed? =
        analyzer.analyze(declaration)?.let { node ->
            declaration.containingFile?.virtualFile?.path?.let { nodeFiles[node.id.value] = it }
            Analyzed(node, extractor.outgoing(declaration))
        }

    private fun addNodes(added: List<Analyzed>, anchor: Point?, near: Near? = null, parentId: String? = null) {
        val fresh = added.distinctBy { it.node.id }.filter { it.node.id.value !in nodes }
        fresh.forEach { nodes[it.node.id.value] = it.node }
        when {
            fresh.isNotEmpty() -> {
                send(Outbound.NodesAdded(fresh.map { PlacedNode(it.node, parent = parentId) }, anchor, near))
                sendOutgoing(fresh)
                enqueueIncoming(fresh.map { it.node.id.value })
            }
            added.isNotEmpty() && near == null -> send(Outbound.FocusNode(added.first().node.id.value))
        }
    }

    private fun sendOutgoing(analyzed: List<Analyzed>) {
        if (analyzed.isNotEmpty()) send(Outbound.Relations(analyzed.map { NodeRelations(it.node.id.value, outgoing = it.outgoing) }))
    }

    private fun enqueueIncoming(ids: List<String>) {
        incomingQueue += ids
        if (!incomingTaskRunning && incomingQueue.isNotEmpty()) startIncomingTask()
    }

    /** Searches usages node by node; the user can cancel it from the progress bar, leaving those nodes "unknown". */
    private fun startIncomingTask() {
        val batch = incomingQueue.toList()
        incomingQueue.clear()
        incomingTaskRunning = true
        object : Task.Backgroundable(project, "Finding usages for Caldo Graph", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                batch.forEachIndexed { index, id ->
                    if (disposed) return
                    indicator.checkCanceled()
                    indicator.fraction = index.toDouble() / batch.size
                    indicator.text2 = NodeId(id).fqName
                    val incoming = ReadAction.nonBlocking(Callable {
                        analyzer.locate(NodeId(id))?.let { incomingFinder.find(NodeId(id), it) }
                    })
                        .inSmartMode(project)
                        .wrapProgress(indicator)
                        .executeSynchronously() ?: return@forEachIndexed
                    ApplicationManager.getApplication().invokeLater({
                        if (id in nodes) {
                            this@GraphController.incoming[id] = incoming
                            send(Outbound.Relations(listOf(NodeRelations(id, incoming = incoming))))
                        }
                    }, ModalityState.any())
                }
            }

            override fun onFinished() {
                incomingTaskRunning = false
                if (incomingQueue.isNotEmpty() && !disposed) startIncomingTask()
            }
        }.queue()
    }

    private fun addChoices(chosen: List<Choice>, anchor: Point?) {
        readAction({ chosen.mapNotNull { it.pointer.element?.let(::analyzeWithRelations) } }) { addNodes(it, anchor) }
    }

    private fun choose(title: String, choices: List<Choice>, onChosen: (List<Choice>) -> Unit) {
        DeclarationChooserPopup(title, choices, { it.name }, { it.location }, onChosen).show(component)
    }

    private fun saveNodes(saved: List<NodeState>) {
        stateService.update(diagramId) { nodes = saved.toMutableList() }
    }

    private fun <T> readAction(compute: () -> T, onEdt: (T) -> Unit) {
        ReadAction.nonBlocking(Callable { compute() })
            .inSmartMode(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any()) { onEdt(it) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private class Choice(val name: String, val location: String, val pointer: SmartPsiElementPointer<KtDeclaration>)

    private companion object {
        const val REFRESH_DELAY_MS = 800
    }

    private class Prepared(val nodes: List<Analyzed>, val optional: List<Choice>, val ambiguous: List<Choice>)

    private fun choice(declaration: KtDeclaration): Choice {
        val kind = if (declaration is KtNamedFunction) "fun " else ""
        val location = declaration.containingKtFile.packageFqName.asString()
        return Choice("$kind${declaration.name}", location, SmartPointerManager.createPointer(declaration))
    }

    private fun findMember(declaration: KtDeclaration, name: String): KtDeclaration? = when (declaration) {
        is KtClassOrObject -> declaration.primaryConstructorParameters.firstOrNull { it.name == name && it.hasValOrVar() }
            ?: declaration.declarations.firstOrNull { it.name == name }
        is KtNamedFunction -> declaration.valueParameters.firstOrNull { it.name == name }
        else -> null
    }

    private fun notFoundNode(id: NodeId, saved: NodeState) = GraphNode(
        id = id,
        kind = runCatching { NodeKind.valueOf(saved.kind.orEmpty()) }.getOrDefault(NodeKind.OTHER),
        name = saved.name ?: id.fqName.substringAfterLast('.'),
        packageName = saved.packageName.orEmpty(),
        notFound = true,
    )
}
