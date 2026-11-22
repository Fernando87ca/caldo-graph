package dev.classgraph.analysis

import dev.classgraph.analysis.DeclarationSearch.Kind
import dev.classgraph.testing.KotlinFixtureTestCase

class DeclarationSearchTest : KotlinFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "app/plan/PlanCreationViewModel.kt",
            "package app.plan\n\nclass PlanCreationViewModel\ninterface PlanRepository\nfun helper() {}\n",
        )
        myFixture.addFileToProject(
            "app/plan/PlanPage.kt",
            "package app.plan\n\nimport androidx.compose.runtime.Composable\n\n@Composable fun PlanPage() {}\nfun PlanPageNotComposable() {}\n",
        )
    }

    private fun find(pattern: String): List<String> {
        val search = DeclarationSearch(project)
        return search.find(pattern, search.names()).map { "${it.kind} ${it.name} ${it.location}" }
    }

    fun `test camel hump matching finds classes and their files`() {
        val results = find("PCVM")
        assertEquals("${Kind.CLASS} PlanCreationViewModel app.plan", results.first())
        assertTrue(results.any { it.startsWith("${Kind.FILE} PlanCreationViewModel.kt") })
    }

    fun `test interfaces and composables are found, plain functions and lowercase names are not`() {
        assertTrue("${Kind.INTERFACE} PlanRepository app.plan" in find("PlanRepo"))
        assertTrue("${Kind.FUNCTION} PlanPage app.plan" in find("PlanPage"))
        assertTrue(find("PlanPageNot").none { it.startsWith("${Kind.FUNCTION}") })
        assertTrue(find("helper").none { it.startsWith("${Kind.FUNCTION}") })
    }

    fun `test empty pattern finds nothing`() {
        assertEquals(emptyList<String>(), find("  "))
    }
}
