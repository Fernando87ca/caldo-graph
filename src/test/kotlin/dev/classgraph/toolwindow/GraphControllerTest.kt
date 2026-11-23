package dev.classgraph.toolwindow

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import dev.classgraph.bridge.Outbound
import dev.classgraph.model.RelationKind
import dev.classgraph.state.GraphStateService
import dev.classgraph.testing.KotlinFixtureTestCase
import javax.swing.JPanel

/** Drives the controller as the tool window does and checks the messages it sends to the web app. */
class GraphControllerTest : KotlinFixtureTestCase() {
    private val sent = mutableListOf<Outbound>()
    private lateinit var controller: GraphController

    override fun setUp() {
        super.setUp()
        val stateService = GraphStateService.getInstance(project)
        stateService.loadState(dev.classgraph.state.GraphState())
        val diagram = stateService.create()
        controller = GraphController(project, diagram.id!!, JPanel(), testRootDisposable) { sent += it }
        controller.restore()
        waitFor("restore") { sent.any { it is Outbound.Graph } }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("Timed out waiting for $what", { condition() }, 20)
    }

    private inline fun <reified T : Outbound> lastOf(): T? = sent.filterIsInstance<T>().lastOrNull()

    private fun incomingOf(id: String) = sent.filterIsInstance<Outbound.Relations>().flatMap { it.items }.lastOrNull { it.id == id && it.incoming != null }?.incoming

    fun `test added node gets outgoing and incoming relations, and follows code edits`() {
        myFixture.addFileToProject("app/Repo.kt", "package app\n\ninterface PlanRepository\n")
        val user = myFixture.addFileToProject("app/UseCase.kt", "package app\n\nclass CreatePlan(private val repository: PlanRepository)\n")
        val repoId = "class:app.PlanRepository"

        controller.add(anchor = null) { it.fromText("PlanRepository") }
        waitFor("node") { lastOf<Outbound.NodesAdded>() != null }
        assertEquals("PlanRepository", lastOf<Outbound.NodesAdded>()!!.nodes.single().node.name)
        waitFor("incoming relations") { incomingOf(repoId) != null }
        assertEquals(listOf("class:app.CreatePlan" to RelationKind.INJECTION), incomingOf(repoId)!!.map { it.source.value to it.kind })

        // A new implementation appears in an edited file: the incoming list is updated without a new usage search.
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(user)!!
            document.insertString(document.textLength, "class PlanRepositoryImpl : PlanRepository\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        controller.refreshFiles(setOf(user.virtualFile))
        waitFor("refresh") { incomingOf(repoId)?.size == 2 }
        assertEquals(
            setOf("class:app.CreatePlan" to RelationKind.INJECTION, "class:app.PlanRepositoryImpl" to RelationKind.IMPLEMENTATION),
            incomingOf(repoId)!!.map { it.source.value to it.kind }.toSet(),
        )
    }

    fun `test node is marked not found when its declaration is deleted`() {
        val file = myFixture.addFileToProject("app/Plan.kt", "package app\n\nclass Plan\n")
        controller.add(anchor = null) { it.fromText("Plan") }
        waitFor("node") { lastOf<Outbound.NodesAdded>() != null }

        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            document.setText("package app\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        controller.refreshFiles(setOf(file.virtualFile))
        waitFor("update") { lastOf<Outbound.NodesUpdated>() != null }
        assertTrue(lastOf<Outbound.NodesUpdated>()!!.nodes.single().notFound)
    }
}
