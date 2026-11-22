package dev.classgraph.analysis

import dev.classgraph.model.ClassificationRule
import dev.classgraph.model.NodeKind
import dev.classgraph.model.RuleTarget
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/** Applies [rules] in order; the first match wins. Call inside a read action, off the EDT. */
class NodeClassifier(private val rules: List<ClassificationRule>) {

    fun classify(declaration: KtDeclaration): NodeKind {
        val facts = Facts(declaration)
        return rules.firstOrNull { facts.matches(it) }?.kind ?: NodeKind.OTHER
    }

    /** What the rules can ask about a declaration; supertypes are resolved lazily, at most once. */
    private class Facts(private val declaration: KtDeclaration) {
        private val name = declaration.name.orEmpty()
        private val klass = declaration as? KtClassOrObject
        private val isInterface = (declaration as? KtClass)?.isInterface() == true
        private val supertypes: List<FqName> by lazy { klass?.let(::allSupertypes).orEmpty() }

        fun matches(rule: ClassificationRule): Boolean =
            targetMatches(rule.target) &&
                rule.nameSuffix.anyOrBlank { name.endsWith(it) || name.removeSuffix("Impl").endsWith(it) } &&
                rule.annotation.anyOrBlank { a -> declaration.annotationEntries.any { it.shortName?.asString() == a.substringAfterLast('.') } } &&
                rule.modifier.anyOrBlank(::hasModifier) &&
                rule.filePattern.anyOrBlank { globToRegex(it).matches(declaration.containingFile.name) } &&
                rule.supertype.anyOrBlank(::hasSupertype)

        private fun targetMatches(target: RuleTarget) = when (target) {
            RuleTarget.ANY -> true
            RuleTarget.CLASS -> klass != null && !isInterface
            RuleTarget.INTERFACE -> isInterface
            RuleTarget.FUNCTION -> declaration is KtNamedFunction
        }

        private fun hasModifier(modifier: String): Boolean = when (modifier) {
            "object" -> declaration is KtObjectDeclaration
            "data" -> (declaration as? KtModifierListOwner)?.hasModifier(KtTokens.DATA_KEYWORD) == true
            "sealed" -> (declaration as? KtModifierListOwner)?.hasModifier(KtTokens.SEALED_KEYWORD) == true
            "enum" -> (declaration as? KtModifierListOwner)?.hasModifier(KtTokens.ENUM_KEYWORD) == true
            "abstract" -> (declaration as? KtModifierListOwner)?.hasModifier(KtTokens.ABSTRACT_KEYWORD) == true
            else -> false
        }

        private fun hasSupertype(pattern: String): Boolean = when {
            pattern.startsWith("*") -> supertypes.any { it.shortName().asString().endsWith(pattern.removePrefix("*")) }
            '.' in pattern -> supertypes.any { it.asString() == pattern }
            else -> supertypes.any { it.shortName().asString() == pattern }
        }

        private fun String.anyOrBlank(predicate: (String) -> Boolean): Boolean {
            val alternatives = split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return alternatives.isEmpty() || alternatives.any(predicate)
        }
    }

    companion object {
        private val VIEW_MODEL = ClassId.topLevel(FqName("androidx.lifecycle.ViewModel"))

        /** Semantic check used by relation extraction (`«obtains»`), independent of the user's rules. */
        fun KaSession.isViewModel(declaration: KtClassOrObject): Boolean =
            (declaration.symbol as? KaNamedClassSymbol)?.defaultType?.isSubtypeOf(VIEW_MODEL) == true

        private fun allSupertypes(declaration: KtClassOrObject): List<FqName> = analyze(declaration) {
            val symbol = declaration.symbol as? KaNamedClassSymbol ?: return@analyze emptyList()
            symbol.defaultType.allSupertypes.mapNotNull { (it as? KaClassType)?.classId?.asSingleFqName() }.toList()
        }

        internal fun globToRegex(glob: String): Regex =
            Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })
    }
}

/** Matches by short name so it also works when Compose is not resolvable (e.g. broken classpath, tests). */
fun KtNamedFunction.isComposable(): Boolean = annotationEntries.any { it.shortName?.asString() == "Composable" }
