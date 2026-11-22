package dev.classgraph.analysis

import dev.classgraph.model.Member
import dev.classgraph.model.MemberKind
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

/** Extracts the public API shown in a node box. Call inside a read action. */
object MemberExtractor {

    fun properties(declaration: KtClassOrObject): List<Member> {
        val constructorProperties = declaration.primaryConstructorParameters.filter { it.hasValOrVar() && it.isPublic() }
        val bodyProperties = declaration.declarations.filterIsInstance<KtProperty>().filter { it.isPublic() }
        return (constructorProperties + bodyProperties).map { Member(it.name.orEmpty(), MemberKind.PROPERTY, ": ${typeText(it)}") }
    }

    fun functions(declaration: KtClassOrObject): List<Member> =
        declaration.declarations.filterIsInstance<KtNamedFunction>().filter { it.isPublic() }.map(::functionMember)

    fun parameters(function: KtNamedFunction): List<Member> =
        function.valueParameters.map { Member(it.name.orEmpty(), MemberKind.PARAMETER, ": ${typeText(it)}") }

    private fun functionMember(function: KtNamedFunction): Member {
        val params = function.valueParameters.joinToString(", ") { "${it.name}: ${typeText(it)}" }
        val returnType = function.typeReference?.text ?: inferredType(function).takeIf { it != "Unit" }
        return Member(function.name.orEmpty(), MemberKind.FUNCTION, "($params)" + (returnType?.let { ": $it" } ?: ""))
    }

    private fun typeText(declaration: KtCallableDeclaration): String = declaration.typeReference?.text ?: inferredType(declaration)

    @OptIn(KaExperimentalApi::class)
    private fun inferredType(declaration: KtCallableDeclaration): String = analyze(declaration) {
        val type = declaration.returnType
        if (type is KaErrorType) "?" else type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
    }

    private fun KtModifierListOwner.isPublic(): Boolean =
        !hasModifier(KtTokens.PRIVATE_KEYWORD) && !hasModifier(KtTokens.PROTECTED_KEYWORD) && !hasModifier(KtTokens.INTERNAL_KEYWORD)
}
