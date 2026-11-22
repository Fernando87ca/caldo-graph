package dev.classgraph.analysis

import dev.classgraph.model.ClassificationRule
import dev.classgraph.model.NodeKind
import dev.classgraph.model.RuleTarget
import dev.classgraph.testing.KotlinFixtureTestCase
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtFile

@OptIn(KaAllowAnalysisOnEdt::class)
class NodeClassifierTest : KotlinFixtureTestCase() {

    private fun classify(rules: List<ClassificationRule>, path: String, text: String, name: String): NodeKind {
        val file = myFixture.addFileToProject(path, text) as KtFile
        val declaration = file.declarations.first { it.name == name }
        return allowAnalysisOnEdt { NodeClassifier(rules).classify(declaration) }
    }

    fun `test first matching rule wins and unmatched declarations are other`() {
        val rules = listOf(
            ClassificationRule(NodeKind.DATA_SOURCE, nameSuffix = "Client"),
            ClassificationRule(NodeKind.MODEL, RuleTarget.CLASS, modifier = "data"),
        )
        assertEquals(NodeKind.DATA_SOURCE, classify(rules, "a/A.kt", "package a\n\ndata class PlanClient(val x: Int)\n", "PlanClient"))
        assertEquals(NodeKind.MODEL, classify(rules, "a/B.kt", "package a\n\ndata class Plan(val x: Int)\n", "Plan"))
        assertEquals(NodeKind.OTHER, classify(rules, "a/C.kt", "package a\n\nclass Clock\n", "Clock"))
    }

    fun `test all conditions of a rule must match`() {
        val rules = listOf(ClassificationRule(NodeKind.COMPOSABLE_PAGE, RuleTarget.FUNCTION, annotation = "Composable", filePattern = "*Screen.kt"))
        val source = "package a\n\nimport androidx.compose.runtime.Composable\n\n@Composable fun Home() {}\nfun Plain() {}\n"
        assertEquals(NodeKind.COMPOSABLE_PAGE, classify(rules, "a/HomeScreen.kt", source, "Home"))
        assertEquals(NodeKind.OTHER, classify(rules, "a/HomeScreen.kt".replace("Home", "Other"), source, "Plain"))
        assertEquals(NodeKind.OTHER, classify(rules, "a/Home.kt", source, "Home"))
    }

    fun `test supertype by fqn and by suffix, through intermediate classes`() {
        myFixture.addFileToProject("a/Base.kt", "package a\n\ninterface Interactor\nabstract class Base : Interactor\n")
        val rules = listOf(
            ClassificationRule(NodeKind.VIEW_MODEL, supertype = "androidx.lifecycle.ViewModel"),
            ClassificationRule(NodeKind.USE_CASE, supertype = "*Interactor"),
        )
        assertEquals(NodeKind.USE_CASE, classify(rules, "a/Load.kt", "package a\n\nclass Load : Base()\n", "Load"))
        assertEquals(NodeKind.VIEW_MODEL, classify(rules, "a/Vm.kt", "package a\n\nclass Vm : androidx.lifecycle.ViewModel()\n", "Vm"))
    }

    fun `test glob patterns`() {
        assertTrue(NodeClassifier.globToRegex("*Page.kt").matches("PlanPage.kt"))
        assertFalse(NodeClassifier.globToRegex("*Page.kt").matches("PlanPageKt"))
    }
}
