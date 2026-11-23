package dev.classgraph.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GraphStateServiceTest : BasePlatformTestCase() {

    private val service get() = GraphStateService.getInstance(project)

    fun `test diagrams are created with unique names, renamed and deleted`() {
        service.loadState(GraphState())
        val first = service.create()
        val second = service.create()
        assertEquals(listOf("Diagram 1", "Diagram 2"), service.diagrams.map { it.name })
        assertFalse(first.id == second.id)

        service.rename(first.id!!, "Checkout")
        service.update(second.id!!) { nodes = mutableListOf(NodeState().apply { id = "class:app.Plan" }) }
        assertEquals("Checkout", service.diagram(first.id!!)!!.name)
        assertEquals(1, service.diagram(second.id!!)!!.nodes.size)

        service.delete(first.id!!)
        assertEquals(listOf(second.id), service.diagrams.map { it.id })
        assertEquals("Diagram 1", service.create().name)
    }

    fun `test the single graph of older versions becomes the first diagram`() {
        service.loadState(GraphState().apply {
            nodes = mutableListOf(NodeState().apply { id = "class:app.Plan"; x = 10f })
            legendVisible = false
            hiddenNodeKinds = mutableListOf("MODEL")
        })
        val diagram = service.diagrams.single()
        assertEquals("Diagram 1", diagram.name)
        assertEquals(listOf("class:app.Plan"), diagram.nodes.map { it.id })
        assertFalse(diagram.legendVisible)
        assertEquals(listOf("MODEL"), diagram.hiddenNodeKinds)
        assertTrue(service.state.nodes.isEmpty())
    }
}
