package dev.classgraph.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

class DeclarationResolverTest : BasePlatformTestCase() {

    fun `test file selects the declaration named like the file and offers the others`() {
        val file = myFixture.addFileToProject(
            "app/PlanPage.kt",
            """
            package app

            private fun PlanScaffold() {}
            fun PlanPage() {}
            fun PlanPagePreview() {}
            """.trimIndent(),
        ) as KtFile

        val resolution = DeclarationResolver(project).fromElements(listOf(file))

        assertEquals(listOf("PlanPage"), resolution.selected.map { it.name })
        assertEquals(listOf("PlanScaffold", "PlanPagePreview"), resolution.optional.map { it.name })
    }

    fun `test re-dropping a shown declaration offers the rest of its file, without the ones already shown`() {
        val file = myFixture.addFileToProject(
            "app/PlanCreationViewModel.kt",
            "package app\n\nclass PlanCreationViewModel\nclass PlanCreationState\nsealed interface PlanCreationAction\n",
        ) as KtFile
        val viewModel = file.declarations.first { it.name == "PlanCreationViewModel" }
        val resolution = DeclarationResolver(project).fromElements(listOf(viewModel))
        val names = { present: Set<String?> -> DeclarationResolver.offerable(resolution, present) { it.name }.map { it.name } }

        assertEquals("nothing to offer for a first drop of a class", emptyList<String>(), names(emptySet()))
        assertEquals(listOf("PlanCreationAction"), names(setOf("PlanCreationViewModel", "PlanCreationState")))
        assertEquals(
            listOf("PlanCreationAction"),
            DeclarationResolver.offerable(DeclarationResolver(project).fromElements(listOf(file)), setOf("PlanCreationState")) { it.name }.map { it.name },
        )
    }

    fun `test file without a matching name falls back to its first class`() {
        val file = myFixture.addFileToProject("app/Models.kt", "package app\n\nfun helper() {}\nclass A\nclass B\n") as KtFile

        val resolution = DeclarationResolver(project).fromFiles(listOf(file.virtualFile))

        assertEquals(listOf("A"), resolution.selected.map { it.name })
    }

    fun `test element inside a class resolves to the class`() {
        myFixture.configureByText("Vm.kt", "package app\n\nclass Vm {\n  fun lo<caret>ad() {}\n}\n")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!

        assertEquals(listOf("Vm"), DeclarationResolver(project).fromElements(listOf(element)).selected.map { it.name })
    }

    fun `test text resolves through the short name index`() {
        myFixture.addFileToProject("app/CreatePlanUseCase.kt", "package app.domain\n\nclass CreatePlanUseCase\n")

        val resolution = DeclarationResolver(project).fromText(" CreatePlanUseCase ")

        assertEquals(listOf("CreatePlanUseCase"), resolution.selected.map { it.name })
    }

    fun `test ambiguous text is offered as a choice`() {
        myFixture.addFileToProject("a/Plan.kt", "package a\n\nclass Plan\n")
        myFixture.addFileToProject("b/Plan.kt", "package b\n\nclass Plan\n")

        val resolution = DeclarationResolver(project).fromText("Plan")

        assertTrue(resolution.selected.isEmpty())
        assertEquals(2, resolution.ambiguous.size)
        assertEquals(listOf("b.Plan"), DeclarationResolver(project).fromText("b.Plan").selected.map { (it as org.jetbrains.kotlin.psi.KtNamedDeclaration).fqName?.asString() })
    }
}
