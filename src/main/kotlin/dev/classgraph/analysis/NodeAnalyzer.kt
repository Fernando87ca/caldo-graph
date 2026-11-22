package dev.classgraph.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import dev.classgraph.model.GraphNode
import dev.classgraph.model.NodeId
import dev.classgraph.settings.ClassGraphSettings
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Builds PSI-free [GraphNode]s and resolves [NodeId]s back to declarations. Call inside a read action. */
class NodeAnalyzer(private val project: Project) {
    private val classifier get() = NodeClassifier(ClassGraphSettings.getInstance(project).rules)

    fun analyze(declaration: KtDeclaration): GraphNode? = when (declaration) {
        is KtClassOrObject -> {
            val fqName = declaration.fqName?.asString() ?: return null
            GraphNode(
                id = NodeId.ofClass(fqName),
                kind = classifier.classify(declaration),
                name = declaration.name.orEmpty(),
                packageName = declaration.containingKtFile.packageFqName.asString(),
                isInterface = (declaration as? KtClass)?.isInterface() == true,
                properties = MemberExtractor.properties(declaration),
                functions = MemberExtractor.functions(declaration),
            )
        }
        is KtNamedFunction -> nodeId(declaration)?.let { id ->
            GraphNode(
                id = id,
                kind = classifier.classify(declaration),
                name = declaration.name.orEmpty(),
                packageName = declaration.containingKtFile.packageFqName.asString(),
                parameters = MemberExtractor.parameters(declaration),
            )
        }
        else -> null
    }

    fun nodeId(declaration: KtDeclaration): NodeId? = when (declaration) {
        is KtClassOrObject -> declaration.fqName?.let { NodeId.ofClass(it.asString()) }
        is KtNamedFunction -> {
            val fqName = declaration.fqName?.asString()
            val path = declaration.containingFile.virtualFile?.path
            if (fqName == null || path == null || !declaration.isTopLevel) null
            else NodeId.ofFunction(fqName, parameterTypes(declaration), path)
        }
        else -> null
    }

    /** Finds the declaration for [id] in project sources, or null when it no longer exists. */
    fun locate(id: NodeId): KtDeclaration? {
        val scope = GlobalSearchScope.projectScope(project)
        if (!id.isFunction) return KotlinFullClassNameIndex.Helper[id.fqName, project, scope].firstOrNull()

        val candidates = KotlinTopLevelFunctionFqnNameIndex.Helper[id.fqName, project, scope]
            .filter { parameterTypes(it).joinToString(",") == id.parameterTypes }
        return candidates.firstOrNull { it.containingFile.virtualFile?.path == id.filePath }
            ?: candidates.firstOrNull()
            ?: id.filePath?.let(::functionInFile)?.takeIf { it.fqName?.asString() == id.fqName }
    }

    private fun functionInFile(path: String): KtNamedFunction? {
        val file = LocalFileSystem.getInstance().findFileByPath(path)?.let { PsiManager.getInstance(project).findFile(it) } as? KtFile
        return file?.declarations?.filterIsInstance<KtNamedFunction>()?.firstOrNull()
    }

    private fun parameterTypes(function: KtNamedFunction): List<String> =
        function.valueParameters.map { it.typeReference?.text?.replace(" ", "") ?: "?" }
}
