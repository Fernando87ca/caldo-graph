package dev.classgraph.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import dev.classgraph.model.NodeId
import dev.classgraph.model.Relation
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Incoming relations of a node: who injects, holds, extends, implements, obtains or calls it.
 * Usages are found with [ReferencesSearch] in project scope; each usage is mapped to its enclosing class or top-level
 * function, and only the relations that this container's own outgoing analysis reports are kept, so both directions agree.
 * Call inside a read action, off the EDT; honours cancellation of the current progress indicator.
 */
class IncomingRelationFinder(private val project: Project) {
    private val extractor = RelationExtractor(project)

    fun find(id: NodeId, declaration: KtDeclaration): List<Relation> {
        val containers = LinkedHashSet<KtDeclaration>()
        ReferencesSearch.search(declaration, GlobalSearchScope.projectScope(project)).forEach(Processor { reference ->
            ProgressManager.checkCanceled()
            containerOf(reference.element)?.takeIf { it != declaration }?.let(containers::add)
            true
        })
        return containers.flatMap { container ->
            ProgressManager.checkCanceled()
            extractor.outgoing(container).filter { it.target == id }
        }
    }

    private fun containerOf(element: PsiElement): KtDeclaration? {
        if (element.getParentOfType<KtImportDirective>(strict = false) != null) return null
        if (element.getParentOfType<KtPackageDirective>(strict = false) != null) return null
        return generateSequence(element.parent) { it.parent }.firstOrNull {
            (it is KtClassOrObject && !it.isLocal) || (it is KtNamedFunction && it.isTopLevel)
        } as? KtDeclaration
    }
}
