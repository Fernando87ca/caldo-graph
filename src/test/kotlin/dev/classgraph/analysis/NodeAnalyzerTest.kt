package dev.classgraph.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.classgraph.model.GraphNode
import dev.classgraph.model.Member
import dev.classgraph.model.MemberKind
import dev.classgraph.model.NodeId
import dev.classgraph.model.NodeKind
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

/** Fixture tests run on the EDT, where production code never calls the Analysis API. */
@OptIn(KaAllowAnalysisOnEdt::class)
class NodeAnalyzerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("androidx/lifecycle/ViewModel.kt", "package androidx.lifecycle\n\nabstract class ViewModel\n")
        myFixture.addFileToProject("androidx/compose/runtime/Composable.kt", "package androidx.compose.runtime\n\nannotation class Composable\n")
    }

    private fun analyze(path: String, text: String, name: String? = null): GraphNode {
        val file = myFixture.addFileToProject(path, text) as KtFile
        val declaration: KtDeclaration = name?.let { n -> file.declarations.first { it.name == n } }
            ?: DeclarationResolver.mainDeclaration(file)!!
        return allowAnalysisOnEdt { NodeAnalyzer(project).analyze(declaration)!! }
    }

    private fun kindOf(source: String, name: String) = analyze("app/$name.kt", "package app\n\n$source\n", name).kind

    fun `test classification rules`() {
        myFixture.addFileToProject("app/Base.kt", "package app\n\nabstract class BaseViewModel : androidx.lifecycle.ViewModel()\n")
        myFixture.addFileToProject("app/PlanRepository.kt", "package app\n\ninterface PlanRepository\n")

        assertEquals(NodeKind.VIEW_MODEL, kindOf("class PlanCreationViewModel : BaseViewModel()", "PlanCreationViewModel"))
        assertEquals(NodeKind.USE_CASE, kindOf("class CreatePlanUseCase", "CreatePlanUseCase"))
        assertEquals(NodeKind.REPOSITORY_INTERFACE, kindOf("interface ChildrenRepository", "ChildrenRepository"))
        assertEquals(NodeKind.REPOSITORY_IMPL, kindOf("class PlanRepositoryImpl : PlanRepository", "PlanRepositoryImpl"))
        assertEquals(NodeKind.DATA_SOURCE, kindOf("class PlanApi", "PlanApi"))
        assertEquals(NodeKind.DATA_SOURCE, kindOf("interface PlanLocalDataSource", "PlanLocalDataSource"))
        assertEquals(NodeKind.NAVIGATOR, kindOf("class PlanCreationNavigator", "PlanCreationNavigator"))
        assertEquals(NodeKind.MODEL, kindOf("data class Plan(val id: String)", "Plan"))
        assertEquals(NodeKind.MODEL, kindOf("sealed interface PlanEffect", "PlanEffect"))
        assertEquals(NodeKind.MODEL, kindOf("enum class Mode { A }", "Mode"))
        assertEquals(NodeKind.MODEL, kindOf("class PlanUi", "PlanUi"))
        assertEquals(NodeKind.OTHER, kindOf("class Clock", "Clock"))
    }

    fun `test composable classification`() {
        val imports = "import androidx.compose.runtime.Composable\n\n"
        assertEquals(NodeKind.COMPOSABLE_PAGE, kindOf("$imports@Composable fun PlanPage() {}", "PlanPage"))
        assertEquals(NodeKind.COMPOSABLE_ROUTER, kindOf("$imports@Composable fun PlanRouter() {}", "PlanRouter"))
        assertEquals(NodeKind.COMPOSABLE, kindOf("$imports@Composable fun PlanCard() {}", "PlanCard"))
        assertEquals(NodeKind.OTHER, kindOf("fun formatPlan() {}", "formatPlan"))
    }

    fun `test public members of a class`() {
        val node = analyze(
            "app/Vm.kt",
            """
            package app

            class Vm(
                getChildren: Any,
                val navigator: String,
                private val createPlan: Any,
                internal val hidden: Int,
            ) {
                val state = Box(Marker())
                val unresolved = undefinedCall()
                private val secret = 1
                fun onAction(action: String, times: Int): Boolean = true
                fun reset() {}
                private fun helper() {}
                protected fun forSubclasses() {}
            }

            class Box<T>(val items: T)
            class Marker
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Member("navigator", MemberKind.PROPERTY, ": String"),
                Member("state", MemberKind.PROPERTY, ": Box<Marker>"),
                Member("unresolved", MemberKind.PROPERTY, ": ?"),
            ),
            node.properties,
        )
        assertEquals(
            listOf(
                Member("onAction", MemberKind.FUNCTION, "(action: String, times: Int): Boolean"),
                Member("reset", MemberKind.FUNCTION, "()"),
            ),
            node.functions,
        )
        assertEquals("app", node.packageName)
    }

    fun `test composable parameters and function identity`() {
        val node = analyze(
            "app/PlanPage.kt",
            "package app\n\n@androidx.compose.runtime.Composable\nfun PlanPage(state: PlanState, onAction: (String) -> Unit) {}\nclass PlanState\n",
        )

        assertEquals(listOf("state: PlanState", "onAction: (String) -> Unit"), node.parameters.map { "${it.name}${it.signature}" })
        assertTrue(node.id.isFunction)
        assertEquals("app.PlanPage", node.id.fqName)
        assertEquals("PlanState,(String)->Unit", node.id.parameterTypes)
    }

    fun `test node ids resolve back to declarations`() {
        val classNode = analyze("app/Plan.kt", "package app\n\nclass Plan\n")
        val functionNode = analyze("app/PlanCard.kt", "package app\n\nfun PlanCard(id: String) {}\nfun PlanCard() {}\n")
        val analyzer = NodeAnalyzer(project)

        assertEquals("Plan", analyzer.locate(classNode.id)?.name)
        val located = analyzer.locate(functionNode.id)
        assertEquals(functionNode, allowAnalysisOnEdt { located?.let(analyzer::analyze) })
        assertNull(analyzer.locate(NodeId.ofClass("app.Gone")))
    }
}
