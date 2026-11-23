package dev.classgraph.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.classgraph.model.ClassificationRule
import dev.classgraph.model.NodeKind
import dev.classgraph.model.RuleTarget
import dev.classgraph.toolwindow.ClassGraphService
import java.awt.BorderLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.TableCellEditor

/** Settings | Tools | Caldo Graph: the ordered classification rules (first match wins). */
class ClassGraphConfigurable(private val project: Project) : Configurable {

    /** Mutable table row, so equal rules can coexist and be edited independently. */
    private class Row(var rule: ClassificationRule)

    private val model = ListTableModel<Row>(
        enumColumn("Kind", NodeKind.entries, { it.kind }) { r, v -> r.copy(kind = v) },
        enumColumn("Applies to", RuleTarget.entries, { it.target }) { r, v -> r.copy(target = v) },
        textColumn("Name suffix", { it.nameSuffix }) { r, v -> r.copy(nameSuffix = v) },
        textColumn("Supertype", { it.supertype }) { r, v -> r.copy(supertype = v) },
        textColumn("Annotation", { it.annotation }) { r, v -> r.copy(annotation = v) },
        textColumn("Modifier", { it.modifier }) { r, v -> r.copy(modifier = v) },
        textColumn("File", { it.filePattern }) { r, v -> r.copy(filePattern = v) },
    )
    private val table = TableView(model)

    override fun getDisplayName() = "Caldo Graph"

    override fun createComponent(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(Row(ClassificationRule(NodeKind.OTHER))) }
            .createPanel()
        val help = JBLabel(
            "<html>Rules are checked from top to bottom; the first rule whose non-empty conditions all match decides the kind. " +
                "Separate alternatives with commas. Supertype: FQN (<code>androidx.lifecycle.ViewModel</code>) or <code>*Suffix</code>. " +
                "Modifier: data, sealed, enum, abstract, object. File: glob such as <code>*Page.kt</code>.</html>",
        )
        val reset = JButton("Reset to Defaults").apply { addActionListener { setRules(ClassificationRule.DEFAULTS) } }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(help, BorderLayout.NORTH)
            add(decorated, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { add(reset, BorderLayout.WEST) }, BorderLayout.SOUTH)
        }
    }

    override fun isModified() = currentRules() != ClassGraphSettings.getInstance(project).rules

    override fun apply() {
        ClassGraphSettings.getInstance(project).rules = currentRules()
        ClassGraphService.getInstance(project).reanalyzeAll()
    }

    override fun reset() = setRules(ClassGraphSettings.getInstance(project).rules)

    private fun currentRules() = model.items.map { it.rule }

    private fun setRules(rules: List<ClassificationRule>) {
        model.items = rules.map(::Row).toMutableList()
    }

    private abstract class RuleColumn<T>(name: String) : ColumnInfo<Row, T>(name) {
        abstract fun get(rule: ClassificationRule): T
        abstract fun update(rule: ClassificationRule, value: T): ClassificationRule

        override fun valueOf(item: Row): T = get(item.rule)
        override fun isCellEditable(item: Row) = true
        override fun setValue(item: Row, value: T) {
            item.rule = update(item.rule, value)
        }
    }

    private fun textColumn(
        name: String,
        getter: (ClassificationRule) -> String,
        setter: (ClassificationRule, String) -> ClassificationRule,
    ): ColumnInfo<Row, String> = object : RuleColumn<String>(name) {
        override fun get(rule: ClassificationRule) = getter(rule)
        override fun update(rule: ClassificationRule, value: String) = setter(rule, value.trim())
    }

    private fun <E : Enum<E>> enumColumn(
        name: String,
        values: List<E>,
        getter: (ClassificationRule) -> E,
        setter: (ClassificationRule, E) -> ClassificationRule,
    ): ColumnInfo<Row, E> = object : RuleColumn<E>(name) {
        override fun get(rule: ClassificationRule) = getter(rule)
        override fun update(rule: ClassificationRule, value: E) = setter(rule, value)
        override fun getEditor(item: Row): TableCellEditor = DefaultCellEditor(ComboBox(values.toTypedArray<Any>()))
    }
}
