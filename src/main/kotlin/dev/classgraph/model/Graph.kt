package dev.classgraph.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NodeKind {
    @SerialName("composablePage") COMPOSABLE_PAGE,
    @SerialName("composableRouter") COMPOSABLE_ROUTER,
    @SerialName("composable") COMPOSABLE,
    @SerialName("navigator") NAVIGATOR,
    @SerialName("viewModel") VIEW_MODEL,
    @SerialName("useCase") USE_CASE,
    @SerialName("repositoryInterface") REPOSITORY_INTERFACE,
    @SerialName("repositoryImpl") REPOSITORY_IMPL,
    @SerialName("dataSource") DATA_SOURCE,
    @SerialName("model") MODEL,
    @SerialName("other") OTHER,
}

@Serializable
enum class MemberKind {
    @SerialName("property") PROPERTY,
    @SerialName("function") FUNCTION,
    @SerialName("parameter") PARAMETER,
}

/** [signature] is what the box shows after the name, e.g. `: StateFlow<State>` or `(id: String): Plan`. */
@Serializable
data class Member(val name: String, val kind: MemberKind, val signature: String)

/**
 * Identity of a declaration, stable across sessions and free of PSI:
 * `class:<fqName>` for classes, `fun:<fqName>(<param types>)@<file path>` for top-level functions.
 */
@Serializable
@JvmInline
value class NodeId(val value: String) {
    val isFunction: Boolean get() = value.startsWith(FUNCTION_PREFIX)

    /** FQN of the class, or package + name of the function. */
    val fqName: String
        get() = if (isFunction) value.removePrefix(FUNCTION_PREFIX).substringBefore('(') else value.removePrefix(CLASS_PREFIX)

    val filePath: String? get() = if (isFunction) value.substringAfterLast(")@", "").ifEmpty { null } else null

    val parameterTypes: String? get() = if (isFunction) value.substringAfter('(').substringBeforeLast(")@") else null

    override fun toString() = value

    companion object {
        private const val CLASS_PREFIX = "class:"
        private const val FUNCTION_PREFIX = "fun:"

        fun ofClass(fqName: String) = NodeId(CLASS_PREFIX + fqName)
        fun ofFunction(fqName: String, parameterTypes: List<String>, filePath: String) =
            NodeId("$FUNCTION_PREFIX$fqName(${parameterTypes.joinToString(",")})@$filePath")
    }
}

@Serializable
data class GraphNode(
    val id: NodeId,
    val kind: NodeKind,
    val name: String,
    val packageName: String,
    val isInterface: Boolean = false,
    val properties: List<Member> = emptyList(),
    val functions: List<Member> = emptyList(),
    val parameters: List<Member> = emptyList(),
    val notFound: Boolean = false,
)

@Serializable
enum class RelationKind {
    @SerialName("inheritance") INHERITANCE,
    @SerialName("implementation") IMPLEMENTATION,
    @SerialName("injection") INJECTION,
    @SerialName("composition") COMPOSITION,
    @SerialName("aggregation") AGGREGATION,
    @SerialName("dependency") DEPENDENCY,
    @SerialName("obtains") OBTAINS,
    @SerialName("calls") CALLS,
    @SerialName("renders") RENDERS,
}

/** [source] is the declaration whose code mentions [target] (subclass → superclass, owner → part, caller → callee). */
@Serializable
data class Relation(
    val source: NodeId,
    val target: NodeId,
    val kind: RelationKind,
    val label: String? = null,
    val multiplicity: String? = null,
)
