package dev.classgraph.testing

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** Light fixture whose module depends on kotlin-stdlib and kotlinx-coroutines (see `fixtureLibraries` in the build). */
abstract class KotlinFixtureTestCase : BasePlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = Descriptor

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("androidx/lifecycle/ViewModel.kt", "package androidx.lifecycle\n\nabstract class ViewModel\n")
        myFixture.addFileToProject("androidx/compose/runtime/Composable.kt", "package androidx.compose.runtime\n\nannotation class Composable\n")
    }

    private object Descriptor : LightProjectDescriptor() {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            val jars = System.getProperty("classgraph.fixtureLibraries").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            for (jar in jars) {
                val file = File(jar)
                PsiTestUtil.addLibrary(model, file.nameWithoutExtension, file.parent, file.name)
            }
        }
    }
}
