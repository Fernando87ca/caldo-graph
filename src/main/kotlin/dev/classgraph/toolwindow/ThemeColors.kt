package dev.classgraph.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.classgraph.bridge.Outbound
import java.awt.Color

/** Snapshot of the IDE colors that the web app exposes as `--cg-*` CSS variables. */
object ThemeColors {
    fun current(): Outbound.Theme {
        val font = UIUtil.getLabelFont()
        return Outbound.Theme(
            dark = !JBColor.isBright(),
            colors = mapOf(
                "background" to UIUtil.getPanelBackground().css(),
                "foreground" to UIUtil.getLabelForeground().css(),
                "secondary-foreground" to UIUtil.getContextHelpForeground().css(),
                "border" to JBColor.border().css(),
                "panel" to JBUI.CurrentTheme.ToolWindow.background().css(),
                "accent" to JBUI.CurrentTheme.Link.Foreground.ENABLED.css(),
                "selection" to UIUtil.getListSelectionBackground(true).css(),
                "font" to "\"${font.family}\", -apple-system, sans-serif",
                "font-size" to "${font.size}px",
            ),
        )
    }

    private fun Color.css(): String = "#%02x%02x%02x".format(red, green, blue)
}
