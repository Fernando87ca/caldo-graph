package dev.classgraph.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.classgraph.analysis.NodeClassifier.Companion.isViewModel
import dev.classgraph.model.NodeId
import dev.classgraph.model.Relation
import dev.classgraph.model.RelationKind
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.lexer.KtTokens

/**
 * Outgoing UML relations of a class or top-level function towards other declarations of the project.
 * Call inside a read action, off the EDT.
 */
class RelationExtractor(private val project: Project) {
    private val analyzer = NodeAnalyzer(project)
    private val fileIndex = ProjectFileIndex.getInstance(project)

    /** A project class reached through a type, with the multiplicity implied by its wrappers. */
    private class TypeTarget(val declaration: KtClassOrObject, val multiplicity: String)

    fun outgoing(declaration: KtDeclaration): List<Relation> {
        val source = analyzer.nodeId(declaration) ?: return emptyList()
        val raw = analyze(declaration) {
            when (declaration) {
                is KtClassOrObject -> classRelations(declaration)
                is KtNamedFunction -> functionRelations(declaration)
                else -> emptyList()
            }
        }
        return merge(source, raw)
    }

    private data class RawRelation(val target: KtDeclaration, val kind: RelationKind, val label: String?, val multiplicity: String?)

    private fun KaSession.classRelations(declaration: KtClassOrObject): List<RawRelation> {
        val result = mutableListOf<RawRelation>()
        val isInterface = (declaration as? KtClass)?.isInterface() == true

        for (entry in declaration.superTypeListEntries) {
            val target = entry.typeReference?.let { projectClass(it.type) } ?: continue
            val targetIsInterface = (target.declaration as? KtClass)?.isInterface() == true
            val kind = if (targetIsInterface && !isInterface && entry !is KtSuperTypeCallEntry) RelationKind.IMPLEMENTATION else RelationKind.INHERITANCE
            result += RawRelation(target.declaration, kind, null, null)
        }

        // With constructor injection (Koin), every primary constructor parameter is a dependency the class does not create.
        for (parameter in declaration.primaryConstructorParameters) {
            val target = parameter.typeReference?.let { projectClass(it.type) } ?: continue
            result += RawRelation(target.declaration, RelationKind.INJECTION, parameter.name, target.multiplicity)
        }

        for (property in declaration.declarations.filterIsInstance<KtProperty>()) {
            if (property.receiverTypeReference != null) continue
            val target = projectClass(property.returnType) ?: continue
            val creation = property.initializer ?: property.delegateExpression
            val kind = if (creation != null && constructs(creation, target.declaration)) RelationKind.COMPOSITION else RelationKind.AGGREGATION
            result += RawRelation(target.declaration, kind, property.name, target.multiplicity)
        }

        for (function in declaration.declarations.filterIsInstance<KtNamedFunction>()) {
            if (!function.isPublicApi()) continue
            result += signatureDependencies(function)
        }
        return result
    }

    private fun KaSession.functionRelations(function: KtNamedFunction): List<RawRelation> {
        if (!function.isComposable()) return signatureDependencies(function)

        val result = mutableListOf<RawRelation>()
        for (parameter in function.valueParameters) {
            val target = parameter.typeReference?.let { projectClass(it.type) } ?: continue
            val name = target.declaration.name.orEmpty()
            val kind = when {
                isViewModel(target.declaration) -> RelationKind.OBTAINS
                name.endsWith("State") || name.endsWith("Ui") -> RelationKind.RENDERS
                else -> RelationKind.DEPENDENCY
            }
            result += RawRelation(target.declaration, kind, parameter.name, target.multiplicity)
        }

        val body = function.bodyExpression ?: function.bodyBlockExpression ?: return result
        for (call in PsiTreeUtil.findChildrenOfType(body, KtCallExpression::class.java)) {
            val calleeName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: continue
            val variable = call.getParentOfType<KtProperty>(strict = true)?.takeIf { it.initializer?.let { init -> PsiTreeUtil.isAncestor(init, call, false) } == true }
            when (calleeName) {
                in OBTAIN_CALLS -> projectClass(call.expressionType)?.let {
                    result += RawRelation(it.declaration, RelationKind.OBTAINS, variable?.name, null)
                }
                in INJECT_CALLS -> projectClass(call.expressionType)?.let {
                    result += RawRelation(it.declaration, RelationKind.INJECTION, variable?.name, it.multiplicity)
                }
                else -> {
                    val callee = (call.calleeExpression as KtNameReferenceExpression).mainReference.resolve() as? KtNamedFunction
                    if (callee != null && callee != function && callee.isTopLevel && callee.isComposable() && isInProject(callee)) {
                        result += RawRelation(callee, RelationKind.CALLS, null, null)
                    }
                }
            }
        }
        return result
    }

    private fun KaSession.signatureDependencies(function: KtNamedFunction): List<RawRelation> {
        val parameters = function.valueParameters.mapNotNull { p ->
            p.typeReference?.let { projectClass(it.type) }?.let { RawRelation(it.declaration, RelationKind.DEPENDENCY, p.name, null) }
        }
        val returned = projectClass(function.returnType)?.let { RawRelation(it.declaration, RelationKind.DEPENDENCY, null, null) }
        return parameters + listOfNotNull(returned)
    }

    /** Unwraps nullability, collections and reactive/holder wrappers until a project class is found. */
    private fun KaSession.projectClass(type: KaType?): TypeTarget? {
        var current = type?.fullyExpandedType ?: return null
        var optional = current.isMarkedNullable
        var many = false
        repeat(MAX_WRAPPER_DEPTH) {
            val classType = current as? KaClassType ?: return null
            val fqName = classType.classId.asSingleFqName().asString()
            val next = when (fqName) {
                in COLLECTIONS -> {
                    many = true
                    classType.typeArguments.lastOrNull()?.type
                }
                in WRAPPERS -> classType.typeArguments.firstOrNull()?.type
                else -> {
                    val declaration = classType.symbol.psi as? KtClassOrObject ?: return null
                    if (!isInProject(declaration)) return null
                    return TypeTarget(declaration, if (many) "*" else if (optional) "0..1" else "1")
                }
            }
            current = next?.fullyExpandedType ?: return null
            if (current.isMarkedNullable) optional = true
        }
        return null
    }

    /** Whether [expression] calls a constructor of [target] (e.g. `= Foo(...)` or `MutableStateFlow(Foo())`). */
    private fun constructs(expression: KtExpression, target: KtClassOrObject): Boolean {
        val calls = PsiTreeUtil.findChildrenOfType(expression, KtCallExpression::class.java) + listOfNotNull(expression as? KtCallExpression)
        return calls.any { call ->
            when (val resolved = (call.calleeExpression as? KtNameReferenceExpression)?.mainReference?.resolve()) {
                is KtClassOrObject -> resolved == target
                is KtConstructor<*> -> resolved.getContainingClassOrObject() == target
                else -> false
            }
        }
    }

    private fun isInProject(element: PsiElement): Boolean {
        val file = element.containingFile?.virtualFile ?: return false
        return fileIndex.isInSourceContent(file)
    }

    /** One relation per target and kind; labels of repeated relations are joined. */
    private fun merge(source: NodeId, raw: List<RawRelation>): List<Relation> {
        val byTarget = raw.mapNotNull { r -> analyzer.nodeId(r.target)?.takeIf { it != source }?.let { it to r } }
        val structural = byTarget.filter { (_, r) -> r.kind != RelationKind.DEPENDENCY }.map { it.first }.toSet()
        return byTarget
            .filter { (id, r) -> r.kind != RelationKind.DEPENDENCY || id !in structural }
            .groupBy { (id, r) -> id to r.kind }
            .map { (key, group) ->
                Relation(
                    source = source,
                    target = key.first,
                    kind = key.second,
                    label = group.mapNotNull { it.second.label }.distinct().joinToString(", ").ifEmpty { null },
                    multiplicity = group.mapNotNull { it.second.multiplicity }.let { m -> if ("*" in m) "*" else m.firstOrNull() },
                )
            }
    }

    private fun KtNamedFunction.isPublicApi(): Boolean =
        !hasModifier(KtTokens.PRIVATE_KEYWORD) && !hasModifier(KtTokens.PROTECTED_KEYWORD) && !hasModifier(KtTokens.INTERNAL_KEYWORD)

    companion object {
        private const val MAX_WRAPPER_DEPTH = 6

        private val COLLECTIONS = setOf(
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.Set", "kotlin.collections.MutableSet",
            "kotlin.collections.Collection", "kotlin.collections.MutableCollection", "kotlin.collections.Iterable",
            "kotlin.collections.Map", "kotlin.collections.MutableMap", "kotlin.sequences.Sequence", "kotlin.Array",
            "kotlinx.collections.immutable.ImmutableList", "kotlinx.collections.immutable.PersistentList",
            "kotlinx.collections.immutable.ImmutableSet", "kotlinx.collections.immutable.ImmutableMap",
        )

        private val WRAPPERS = setOf(
            "kotlinx.coroutines.flow.Flow", "kotlinx.coroutines.flow.StateFlow", "kotlinx.coroutines.flow.MutableStateFlow",
            "kotlinx.coroutines.flow.SharedFlow", "kotlinx.coroutines.flow.MutableSharedFlow",
            "kotlinx.coroutines.channels.Channel", "kotlinx.coroutines.channels.ReceiveChannel", "kotlinx.coroutines.Deferred",
            "androidx.lifecycle.LiveData", "androidx.lifecycle.MutableLiveData",
            "androidx.compose.runtime.State", "androidx.compose.runtime.MutableState",
            "kotlin.Lazy", "kotlin.Result",
        )

        /** Composable helpers whose result type is the obtained ViewModel. */
        private val OBTAIN_CALLS = setOf("koinViewModel", "koinNavViewModel", "viewModel", "hiltViewModel")
        private val INJECT_CALLS = setOf("koinInject")
    }
}
