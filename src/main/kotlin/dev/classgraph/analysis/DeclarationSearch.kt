package dev.classgraph.analysis

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/**
 * Name search over the project's Kotlin classes, top-level composables (capitalized functions) and `.kt` files,
 * with the platform's camel-hump matching ("PCVM" finds `PlanCreationViewModel`). Call inside a read action.
 */
class DeclarationSearch(private val project: Project) {
    private val scope = GlobalSearchScope.projectScope(project)

    enum class Kind { CLASS, INTERFACE, FUNCTION, FILE }

    /** One concrete match; [location] is the package for declarations and the project-relative directory for files. */
    class Result(val name: String, val location: String, val kind: Kind, val element: PsiElement)

    /** All searchable names, collected once per popup. */
    class Names(val classes: List<String>, val functions: List<String>, val files: List<String>)

    fun names(): Names {
        val classes = sortedSetOf<String>()
        val functions = sortedSetOf<String>()
        val files = sortedSetOf<String>()
        StubIndex.getInstance().processAllKeys(KotlinClassShortNameIndex.Helper.indexKey, Processor { classes += it; true }, scope, null)
        StubIndex.getInstance().processAllKeys(
            KotlinFunctionShortNameIndex.Helper.indexKey,
            Processor { name -> if (name.firstOrNull()?.isUpperCase() == true) functions += name; true },
            scope,
            null,
        )
        FilenameIndex.processAllFileNames(Processor { name -> if (name.endsWith(".kt")) files += name; true }, scope, null)
        return Names(classes.toList(), functions.toList(), files.toList())
    }

    /** Best matches first (classes and composables before files of the same quality), at most [limit] results. */
    fun find(pattern: String, names: Names, limit: Int = 40): List<Result> {
        val text = pattern.trim()
        if (text.isEmpty()) return emptyList()
        val matcher = NameUtil.buildMatcher("*$text").build()
        fun ranked(candidates: List<String>, kind: Kind) =
            candidates.filter { matcher.matches(it) }.map { Triple(it, kind, matcher.matchingDegree(it)) }

        val ranked = (ranked(names.classes, Kind.CLASS) + ranked(names.functions, Kind.FUNCTION) +
            ranked(names.files, Kind.FILE).map { (n, k, d) -> Triple(n, k, d - 1) })
            .sortedWith(compareByDescending<Triple<String, Kind, Int>> { it.third }.thenBy { it.first.length }.thenBy { it.first })

        val results = mutableListOf<Result>()
        for ((name, kind) in ranked) {
            if (results.size >= limit) break
            results += when (kind) {
                Kind.FILE -> files(name)
                Kind.FUNCTION -> KotlinFunctionShortNameIndex.Helper[name, project, scope]
                    .filter { it.isTopLevel && it.isComposable() }
                    .map { Result(name, it.containingKtFile.packageFqName.asString(), Kind.FUNCTION, it) }
                else -> KotlinClassShortNameIndex.Helper[name, project, scope].map(::classResult)
            }
        }
        return results.take(limit)
    }

    private fun classResult(declaration: KtClassOrObject): Result {
        val isInterface = (declaration as? KtClass)?.isInterface() == true
        val location = declaration.fqName?.parent()?.asString() ?: declaration.containingKtFile.packageFqName.asString()
        return Result(declaration.name.orEmpty(), location, if (isInterface) Kind.INTERFACE else Kind.CLASS, declaration)
    }

    private fun files(name: String): List<Result> {
        val psiManager = PsiManager.getInstance(project)
        val basePath = project.basePath?.let { "$it/" }.orEmpty()
        return FilenameIndex.getVirtualFilesByName(name, scope).mapNotNull { file ->
            val psi = psiManager.findFile(file) as? KtFile ?: return@mapNotNull null
            Result(name, file.parent.path.removePrefix(basePath), Kind.FILE, psi)
        }
    }
}
