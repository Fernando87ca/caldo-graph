package dev.classgraph.bridge

import dev.classgraph.model.GraphNode
import dev.classgraph.model.Member
import dev.classgraph.model.MemberKind
import dev.classgraph.model.NodeId
import dev.classgraph.model.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test
    fun `decodes inbound messages`() {
        assertEquals(Inbound.Ping(42), decodeInbound("""{"v":1,"type":"ping","payload":{"nonce":42}}"""))
        assertEquals(Inbound.TextFocus(true), decodeInbound("""{"v":1,"type":"textFocus","payload":{"active":true}}"""))
        assertEquals(Inbound.Ready, decodeInbound("""{"v":1,"type":"ready","payload":{}}"""))
        assertEquals(Inbound.Navigate("class:a.B", "load"), decodeInbound("""{"v":1,"type":"navigate","payload":{"id":"class:a.B","member":"load"}}"""))
        assertEquals(
            Inbound.GraphChanged(listOf(NodeLayout("class:a.B", 1.5, 2.0, listOf("functions"))), legendVisible = false),
            decodeInbound("""{"v":1,"type":"graphChanged","payload":{"nodes":[{"id":"class:a.B","x":1.5,"y":2,"collapsed":["functions"]}],"legendVisible":false}}"""),
        )
    }

    @Test
    fun `rejects other versions and unknown types`() {
        assertThrows(ProtocolException::class.java) { decodeInbound("""{"v":2,"type":"ping","payload":{"nonce":1}}""") }
        assertThrows(ProtocolException::class.java) { decodeInbound("""{"v":1,"type":"nope","payload":{}}""") }
    }

    @Test
    fun `encodes nodes with their ids as plain strings`() {
        val node = GraphNode(
            id = NodeId.ofClass("a.B"),
            kind = NodeKind.VIEW_MODEL,
            name = "B",
            packageName = "a",
            properties = listOf(Member("state", MemberKind.PROPERTY, ": State")),
        )
        val json = encodeOutbound(Outbound.NodesAdded(listOf(PlacedNode(node)), Point(1.0, 2.0)))
        assertEquals(
            """{"v":1,"type":"nodesAdded","payload":{"nodes":[{"node":{"id":"class:a.B","kind":"viewModel","name":"B","packageName":"a",""" +
                """"isInterface":false,"properties":[{"name":"state","kind":"property","signature":": State"}],"functions":[],"parameters":[],""" +
                """"notFound":false},"position":null,"collapsed":[],"parent":null}],"anchor":{"x":1.0,"y":2.0},"near":null}}""",
            json,
        )
    }

    @Test
    fun `function node ids keep fq name, parameter types and file`() {
        val id = NodeId.ofFunction("a.PlanPage", listOf("PlanState", "(String)->Unit"), "/p/a/PlanPage.kt")
        assertEquals("a.PlanPage", id.fqName)
        assertEquals("PlanState,(String)->Unit", id.parameterTypes)
        assertEquals("/p/a/PlanPage.kt", id.filePath)
    }
}
