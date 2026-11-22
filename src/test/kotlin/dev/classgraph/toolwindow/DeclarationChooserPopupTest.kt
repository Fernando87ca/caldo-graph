package dev.classgraph.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test

class DeclarationChooserPopupTest {
    private val names = listOf("PlanCreationAction", "PlanPreferencesSheet", "PlanCreationState", "fun draftFor", "fun toRequest")
    private fun match(text: String) = DeclarationChooserPopup.matching(names, { it }, text)

    @Test
    fun `filters by any part of the name, ignoring case`() {
        assertEquals(listOf("PlanCreationState"), match("state"))
        assertEquals(listOf("fun draftFor"), match("draft"))
    }

    @Test
    fun `supports camel humps and shows everything for a blank filter`() {
        assertEquals(listOf("PlanCreationAction"), match("PCA"))
        assertEquals(names, match("  "))
    }
}
