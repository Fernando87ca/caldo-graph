package dev.classgraph.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.psi.PsiElement
import dev.classgraph.toolwindow.ClassGraphService

/** Adds the selected declaration(s) — from the Project view, Structure view or editor — to the graph. */
class AddToClassGraphAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && (
            !e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY).isNullOrEmpty() ||
                e.getData(CommonDataKeys.PSI_ELEMENT) != null ||
                e.getData(CommonDataKeys.PSI_FILE) != null
            )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedText = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        val elements: List<PsiElement> = e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(e.getData(CommonDataKeys.PSI_ELEMENT) ?: e.getData(CommonDataKeys.PSI_FILE))

        ClassGraphService.getInstance(project).withController { controller ->
            controller.add(anchor = null) { resolver ->
                if (!selectedText.isNullOrBlank()) resolver.fromText(selectedText)
                else resolver.fromElements(elements.filter { it.isValid })
            }
        }
    }
}
