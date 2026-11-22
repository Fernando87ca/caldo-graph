package dev.classgraph.analysis

import dev.classgraph.model.RelationKind
import dev.classgraph.model.RelationKind.AGGREGATION
import dev.classgraph.model.RelationKind.CALLS
import dev.classgraph.model.RelationKind.COMPOSITION
import dev.classgraph.model.RelationKind.DEPENDENCY
import dev.classgraph.model.RelationKind.IMPLEMENTATION
import dev.classgraph.model.RelationKind.INHERITANCE
import dev.classgraph.model.RelationKind.INJECTION
import dev.classgraph.model.RelationKind.OBTAINS
import dev.classgraph.model.RelationKind.RENDERS
import dev.classgraph.testing.KotlinFixtureTestCase
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtDeclaration

@OptIn(KaAllowAnalysisOnEdt::class)
class RelationTest : KotlinFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("org/koin/Koin.kt", "package org.koin\n\nfun <T> koinViewModel(): T = TODO()\nfun <T> koinInject(): T = TODO()\n")
        myFixture.addFileToProject(
            "app/domain/Plans.kt",
            """
            package app.domain

            interface PlanRepository { fun create(request: PlanRequest): Plan }
            data class PlanRequest(val id: String)
            data class Plan(val id: String)
            interface CreatePlanUseCase { suspend operator fun invoke(request: PlanRequest): Plan }
            class CreatePlanUseCaseImpl(private val repository: PlanRepository) : CreatePlanUseCase {
                override suspend fun invoke(request: PlanRequest): Plan = repository.create(request)
            }
            class PlanRepositoryImpl(private val api: PlanApi) : PlanRepository {
                override fun create(request: PlanRequest): Plan = api.post(request)
            }
            class PlanApi { fun post(request: PlanRequest): Plan = Plan(request.id) }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "app/ui/PlanViewModel.kt",
            """
            package app.ui

            import app.domain.CreatePlanUseCase
            import app.domain.Plan
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow

            abstract class BaseViewModel : androidx.lifecycle.ViewModel()
            data class PlanState(val plans: List<Plan> = emptyList())
            class GetChildrenUseCase
            class PlanNavigator
            class Helper
            sealed interface PlanAction

            class PlanViewModel(
                getChildren: GetChildrenUseCase,
                private val createPlan: CreatePlanUseCase,
                val navigator: PlanNavigator?,
            ) : BaseViewModel() {
                val state: StateFlow<PlanState> = MutableStateFlow(PlanState())
                val history: List<Plan> = emptyList()
                private val helper = Helper()
                val title: String = ""
                fun onAction(action: PlanAction) {}
                fun latest(): Plan? = history.lastOrNull()
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "app/ui/PlanRouter.kt",
            """
            package app.ui

            import androidx.compose.runtime.Composable
            import org.koin.koinInject
            import org.koin.koinViewModel

            @Composable
            fun PlanRouter() {
                val vm = koinViewModel<PlanViewModel>()
                val navigator = koinInject<PlanNavigator>()
                PlanPage(state = vm.state.value, onAction = vm::onAction)
            }

            @Composable
            fun PlanPage(state: PlanState, onAction: (PlanAction) -> Unit) {
                listOf(1).forEach { PlanCard() }
            }

            @Composable
            fun PlanCard() {}
            """.trimIndent(),
        )
    }

    private fun klass(fqName: String): KtDeclaration =
        KotlinFullClassNameIndex.Helper[fqName, project, GlobalSearchScope.projectScope(project)].single()

    private fun function(fqName: String): KtDeclaration =
        KotlinTopLevelFunctionFqnNameIndex.Helper[fqName, project, GlobalSearchScope.projectScope(project)].single()

    /** Relations rendered as `KIND target [label] (multiplicity)` for readable assertions. */
    private fun outgoing(declaration: KtDeclaration): Set<String> = allowAnalysisOnEdt {
        RelationExtractor(project).outgoing(declaration).map { r ->
            buildString {
                append("${r.kind} ${r.target.fqName}")
                r.label?.let { append(" [$it]") }
                r.multiplicity?.let { append(" ($it)") }
            }
        }.toSet()
    }

    private fun incoming(declaration: KtDeclaration): Set<String> = allowAnalysisOnEdt {
        val id = NodeAnalyzer(project).nodeId(declaration)!!
        IncomingRelationFinder(project).find(id, declaration).map { "${it.kind} from ${it.source.fqName}" }.toSet()
    }

    fun `test view model relations`() {
        assertEquals(
            setOf(
                "$INHERITANCE app.ui.BaseViewModel",
                "$INJECTION app.ui.GetChildrenUseCase [getChildren] (1)",
                "$INJECTION app.domain.CreatePlanUseCase [createPlan] (1)",
                "$INJECTION app.ui.PlanNavigator [navigator] (0..1)",
                "$COMPOSITION app.ui.PlanState [state] (1)",
                "$AGGREGATION app.domain.Plan [history] (*)",
                "$COMPOSITION app.ui.Helper [helper] (1)",
                "$DEPENDENCY app.ui.PlanAction [action]",
            ),
            outgoing(klass("app.ui.PlanViewModel")),
        )
    }

    fun `test implementation, injection and signature dependencies`() {
        assertEquals(
            setOf(
                "$IMPLEMENTATION app.domain.CreatePlanUseCase",
                "$INJECTION app.domain.PlanRepository [repository] (1)",
                "$DEPENDENCY app.domain.PlanRequest [request]",
                "$DEPENDENCY app.domain.Plan",
            ),
            outgoing(klass("app.domain.CreatePlanUseCaseImpl")),
        )
    }

    fun `test data class aggregates the unwrapped list element`() {
        assertEquals(setOf("$INJECTION app.domain.Plan [plans] (*)"), outgoing(klass("app.ui.PlanState")))
    }

    fun `test router obtains the view model and calls its page`() {
        assertEquals(
            setOf(
                "$OBTAINS app.ui.PlanViewModel [vm]",
                "$INJECTION app.ui.PlanNavigator [navigator] (1)",
                "$CALLS app.ui.PlanPage",
            ),
            outgoing(function("app.ui.PlanRouter")),
        )
    }

    fun `test page renders its state and calls composables inside lambdas`() {
        assertEquals(setOf("$RENDERS app.ui.PlanState [state] (1)", "$CALLS app.ui.PlanCard"), outgoing(function("app.ui.PlanPage")))
    }

    fun `test interface incoming relations include its implementations`() {
        assertEquals(
            setOf("$IMPLEMENTATION from app.domain.PlanRepositoryImpl", "$INJECTION from app.domain.CreatePlanUseCaseImpl"),
            incoming(klass("app.domain.PlanRepository")),
        )
    }

    fun `test composable incoming calls`() {
        assertEquals(setOf("$CALLS from app.ui.PlanRouter"), incoming(function("app.ui.PlanPage")))
        assertEquals(setOf("$OBTAINS from app.ui.PlanRouter"), incoming(klass("app.ui.PlanViewModel")))
    }

    @Suppress("unused")
    private val allKinds = RelationKind.entries
}
