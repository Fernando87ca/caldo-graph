package dev.classgraph.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import dev.classgraph.model.ClassificationRule
import dev.classgraph.model.NodeKind
import dev.classgraph.model.RuleTarget

/** Per-project plugin settings, stored in `.idea/classGraph.xml` so a team can share its rules. */
@Service(Service.Level.PROJECT)
@State(name = "ClassGraphSettings", storages = [Storage("classGraph.xml")])
class ClassGraphSettings : SimplePersistentStateComponent<ClassGraphSettings.SettingsState>(SettingsState()) {

    /** Saved rules, or the defaults when the user never edited them. */
    var rules: List<ClassificationRule>
        get() = if (state.customRules) state.rules.mapNotNull(RuleState::toRule) else ClassificationRule.DEFAULTS
        set(value) {
            val isDefault = value == ClassificationRule.DEFAULTS
            state.customRules = !isDefault
            state.rules = if (isDefault) mutableListOf() else value.map(RuleState::of).toMutableList()
        }

    class SettingsState : BaseState() {
        var customRules by property(false)
        var rules by list<RuleState>()
    }

    class RuleState : BaseState() {
        var kind by string()
        var target by string()
        var nameSuffix by string()
        var supertype by string()
        var annotation by string()
        var modifier by string()
        var filePattern by string()

        fun toRule(): ClassificationRule? {
            val kind = runCatching { NodeKind.valueOf(kind.orEmpty()) }.getOrNull() ?: return null
            return ClassificationRule(
                kind = kind,
                target = runCatching { RuleTarget.valueOf(target.orEmpty()) }.getOrDefault(RuleTarget.ANY),
                nameSuffix = nameSuffix.orEmpty(),
                supertype = supertype.orEmpty(),
                annotation = annotation.orEmpty(),
                modifier = modifier.orEmpty(),
                filePattern = filePattern.orEmpty(),
            )
        }

        companion object {
            fun of(rule: ClassificationRule) = RuleState().apply {
                kind = rule.kind.name
                target = rule.target.name
                nameSuffix = rule.nameSuffix
                supertype = rule.supertype
                annotation = rule.annotation
                modifier = rule.modifier
                filePattern = rule.filePattern
            }
        }
    }

    companion object {
        fun getInstance(project: Project): ClassGraphSettings = project.getService(ClassGraphSettings::class.java)
    }
}
