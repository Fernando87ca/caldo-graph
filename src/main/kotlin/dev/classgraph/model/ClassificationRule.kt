package dev.classgraph.model

enum class RuleTarget { ANY, CLASS, INTERFACE, FUNCTION }

/**
 * "Declaration matching all non-blank conditions → [kind]". Each condition is a comma-separated list of alternatives:
 * - [nameSuffix]: the name, or the name without a trailing `Impl`, ends with one of them (`UseCase, Interactor`)
 * - [supertype]: a direct or indirect supertype is one of them; a value with dots is a FQN, `*Foo` matches simple names ending in `Foo`
 * - [annotation]: annotated with one of them (simple name or FQN)
 * - [modifier]: has one of `data`, `sealed`, `enum`, `abstract`, `object`
 * - [filePattern]: the file name matches one of these globs (`*Page.kt`)
 */
data class ClassificationRule(
    val kind: NodeKind,
    val target: RuleTarget = RuleTarget.ANY,
    val nameSuffix: String = "",
    val supertype: String = "",
    val annotation: String = "",
    val modifier: String = "",
    val filePattern: String = "",
) {
    companion object {
        /** Defaults tuned for Kotlin Multiplatform + Compose + MVI projects; the first matching rule wins. */
        val DEFAULTS: List<ClassificationRule> = listOf(
            ClassificationRule(NodeKind.COMPOSABLE_PAGE, RuleTarget.FUNCTION, annotation = "Composable", nameSuffix = "Page"),
            ClassificationRule(NodeKind.COMPOSABLE_ROUTER, RuleTarget.FUNCTION, annotation = "Composable", nameSuffix = "Router"),
            ClassificationRule(NodeKind.COMPOSABLE, RuleTarget.FUNCTION, annotation = "Composable"),
            ClassificationRule(NodeKind.NAVIGATOR, nameSuffix = "Navigator"),
            ClassificationRule(NodeKind.VIEW_MODEL, supertype = "androidx.lifecycle.ViewModel"),
            ClassificationRule(NodeKind.USE_CASE, nameSuffix = "UseCase"),
            ClassificationRule(NodeKind.USE_CASE, supertype = "*UseCase"),
            ClassificationRule(NodeKind.REPOSITORY_INTERFACE, RuleTarget.INTERFACE, nameSuffix = "Repository"),
            ClassificationRule(NodeKind.REPOSITORY_IMPL, RuleTarget.CLASS, supertype = "*Repository"),
            ClassificationRule(NodeKind.DATA_SOURCE, nameSuffix = "Api, DataSource"),
            ClassificationRule(NodeKind.MODEL, RuleTarget.CLASS, modifier = "data, sealed, enum"),
            ClassificationRule(NodeKind.MODEL, RuleTarget.INTERFACE, modifier = "sealed"),
            ClassificationRule(NodeKind.MODEL, nameSuffix = "State, Event, Ui"),
        )
    }
}
