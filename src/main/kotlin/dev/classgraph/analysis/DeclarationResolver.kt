package dev.classgraph.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * What a drop or an "Add to Caldo Graph" invocation refers to.
 * [selected] is added right away; the user picks from [optional] (any subset) and [ambiguous] (one of them).
 */
data class Resolution(
    val selected: List<KtDeclaration> = emptyList(),
    val optional: List<KtDeclaration> = emptyList(),
    val ambiguous: List<KtDeclaration> = emptyList(),
)

/** Turns dropped PSI elements, files or text into graphable Kotlin declarations. Call inside a read action. */
class DeclarationResolver(private val project: Project) {

    fun fromElements(elements: List<PsiElement>): Resolution = combine(elements.map(::fromElement))

    fun fromFiles(files: List<VirtualFile>): Resolution {
        val psiManager = PsiManager.getInstance(project)
        return combine(files.mapNotNull { psiManager.findFile(it) }.map(::fromElement))
    }

    /** Resolves a selected identifier (short or qualified name) through the project's name indexes. */
    fun fromText(text: String): Resolution {
        val name = text.trim()
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '.' }) return Resolution()
        val shortName = name.substringAfterLast('.')
        val scope = GlobalSearchScope.projectScope(project)
        val classes = KotlinClassShortNameIndex.Helper[shortName, project, scope]
        val functions = KotlinFunctionShortNameIndex.Helper[shortName, project, scope].filter { it.isTopLevel }
        val matches = (classes + functions).filter { name == shortName || it.fqName?.asString() == name }.distinct()
        return if (matches.size == 1) Resolution(selected = matches) else Resolution(ambiguous = matches)
    }

    private fun fromElement(element: PsiElement): Resolution = when (element) {
        is KtFile -> {
            val main = mainDeclaration(element)
            Resolution(selected = listOfNotNull(main), optional = graphableDeclarations(element) - setOfNotNull(main))
        }
        is KtClassOrObject, is KtNamedFunction -> Resolution(selected = listOf(element as KtDeclaration))
        else -> {
            val navigation = element.navigationElement
            if (navigation != element) fromElement(navigation)
            else Resolution(selected = listOfNotNull(element.getParentOfType<KtDeclaration>(strict = false)?.graphableAncestor()))
        }
    }

    private fun KtDeclaration.graphableAncestor(): KtDeclaration? {
        var current: KtDeclaration? = this
        while (current != null && !(current is KtClassOrObject || (current is KtNamedFunction && current.isTopLevel))) {
            current = current.getParentOfType<KtDeclaration>(strict = true)
        }
        return current
    }

    private fun combine(parts: List<Resolution>): Resolution {
        val selected = parts.flatMap { it.selected }.distinct()
        return Resolution(
            selected = selected,
            optional = parts.flatMap { it.optional }.distinct() - selected.toSet(),
            ambiguous = parts.flatMap { it.ambiguous }.distinct(),
        )
    }

    companion object {
        /**
         * What to offer besides the selection: other declarations of dropped files and, when a dropped declaration is
         * already shown (a re-drop means "I want more from here"), the other declarations of its file.
         * Declarations already in the graph are left out.
         */
        fun <Id> offerable(resolution: Resolution, present: Set<Id>, idOf: (KtDeclaration) -> Id?): List<KtDeclaration> {
            val alreadyShown = resolution.selected.filter { idOf(it) in present }
            val siblings = alreadyShown.flatMap { graphableDeclarations(it.containingKtFile) - it }
            return (resolution.optional + siblings)
                .distinct()
                .filter { it !in resolution.selected && idOf(it).let { id -> id != null && id !in present } }
        }

        fun graphableDeclarations(file: KtFile): List<KtDeclaration> =
            file.declarations.filter { it is KtClassOrObject || it is KtNamedFunction }

        /** The declaration named like the file, otherwise the first class, otherwise the first function. */
        fun mainDeclaration(file: KtFile): KtDeclaration? {
            val baseName = file.name.substringBeforeLast('.')
            val candidates = graphableDeclarations(file)
            return candidates.firstOrNull { it.name == baseName }
                ?: candidates.firstOrNull { it is KtClassOrObject }
                ?: candidates.firstOrNull()
        }
    }
}
