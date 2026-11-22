package dev.classgraph.bridge

import dev.classgraph.model.GraphNode
import dev.classgraph.model.Relation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Mirror of `webui/src/protocol.ts`. Bump [PROTOCOL_VERSION] on incompatible changes on both sides. */
const val PROTOCOL_VERSION = 1

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Envelope(val v: Int, val type: String, val payload: JsonElement)

@Serializable
data class Point(val x: Double, val y: Double)

/**
 * A node plus its layout. A null [position] lets the web app place it (near the anchor or [Near] node).
 * [parent] is the node it was expanded from, used by "remove with expanded nodes".
 */
@Serializable
data class PlacedNode(
    val node: GraphNode,
    val position: Point? = null,
    val collapsed: List<String> = emptyList(),
    val parent: String? = null,
)

@Serializable
data class NodeLayout(val id: String, val x: Double, val y: Double, val collapsed: List<String> = emptyList(), val parent: String? = null)

/** Place new nodes on [side] ("left" or "right") of node [id]. */
@Serializable
data class Near(val id: String, val side: String)

/** Relations of one node; a null list means "unchanged / not known yet". */
@Serializable
data class NodeRelations(val id: String, val outgoing: List<Relation>? = null, val incoming: List<Relation>? = null)

@Serializable
data class Filters(val hiddenRelationKinds: List<String> = emptyList(), val hiddenNodeKinds: List<String> = emptyList())

/** Messages from the web app. */
sealed interface Inbound {
    data object Ready : Inbound
    data class Ping(val nonce: Long) : Inbound
    data class Log(val level: String, val message: String) : Inbound

    /** Member name is null when the header was clicked. */
    @Serializable
    data class Navigate(val id: String, val member: String? = null) : Inbound

    /** Full snapshot after any user edit (move, collapse, delete, filters, legend toggle). */
    @Serializable
    data class GraphChanged(val nodes: List<NodeLayout>, val legendVisible: Boolean = true, val filters: Filters = Filters()) : Inbound

    /** Add the nodes [targets] (ids of the other end of pending relations), placed on [side] of node [id]. */
    @Serializable
    data class Expand(val id: String, val side: String, val targets: List<String>) : Inbound

    /** Right-click on the empty canvas at browser client point ([x], [y]): open the search there. */
    @Serializable
    data class OpenSearch(val x: Double, val y: Double) : Inbound

    /** An image of the graph rendered by the web app, as a `data:` URL. */
    @Serializable
    data class ExportImage(val format: String, val dataUrl: String) : Inbound

    /** Search the incoming relations of [id] again (e.g. after a cancelled search). */
    @Serializable
    data class FindIncoming(val id: String) : Inbound
}

/** Messages to the web app. */
sealed interface Outbound {
    val type: String

    @Serializable
    data class Pong(val nonce: Long, val ideVersion: String) : Outbound {
        override val type get() = "pong"
    }

    @Serializable
    data class Theme(val dark: Boolean, val colors: Map<String, String>) : Outbound {
        override val type get() = "theme"
    }

    /** Replaces the whole graph. */
    @Serializable
    data class Graph(val nodes: List<PlacedNode>, val legendVisible: Boolean, val filters: Filters = Filters()) : Outbound {
        override val type get() = "graph"
    }

    /** [anchor] is in browser client coordinates; with neither [anchor] nor [near], nodes go to the viewport center. */
    @Serializable
    data class NodesAdded(val nodes: List<PlacedNode>, val anchor: Point? = null, val near: Near? = null) : Outbound {
        override val type get() = "nodesAdded"
    }

    /** Fresh analysis of nodes already in the graph (members, kind, "not found"); layout is kept. */
    @Serializable
    data class NodesUpdated(val nodes: List<GraphNode>) : Outbound {
        override val type get() = "nodesUpdated"
    }

    @Serializable
    data class Relations(val items: List<NodeRelations>) : Outbound {
        override val type get() = "relations"
    }

    /** Toolbar commands executed by the web app: "autoLayout", "fitView", "exportPng", "exportSvg", "deleteSelection". */
    @Serializable
    data class Command(val name: String) : Outbound {
        override val type get() = "command"
    }

    /** Asks the web app to select and center an existing node. */
    @Serializable
    data class FocusNode(val id: String) : Outbound {
        override val type get() = "focusNode"
    }
}

@Serializable
private data class PingPayload(val nonce: Long)

@Serializable
private data class LogPayload(val level: String = "info", val message: String = "")

class ProtocolException(message: String) : Exception(message)

fun decodeInbound(json: String): Inbound {
    val envelope = ProtocolJson.decodeFromString<Envelope>(json)
    if (envelope.v != PROTOCOL_VERSION) throw ProtocolException("Unsupported protocol version ${envelope.v}")
    val payload = envelope.payload
    return when (envelope.type) {
        "ready" -> Inbound.Ready
        "ping" -> Inbound.Ping(ProtocolJson.decodeFromJsonElement<PingPayload>(payload).nonce)
        "log" -> ProtocolJson.decodeFromJsonElement<LogPayload>(payload).let { Inbound.Log(it.level, it.message) }
        "navigate" -> ProtocolJson.decodeFromJsonElement<Inbound.Navigate>(payload)
        "graphChanged" -> ProtocolJson.decodeFromJsonElement<Inbound.GraphChanged>(payload)
        "expand" -> ProtocolJson.decodeFromJsonElement<Inbound.Expand>(payload)
        "findIncoming" -> ProtocolJson.decodeFromJsonElement<Inbound.FindIncoming>(payload)
        "exportImage" -> ProtocolJson.decodeFromJsonElement<Inbound.ExportImage>(payload)
        "openSearch" -> ProtocolJson.decodeFromJsonElement<Inbound.OpenSearch>(payload)
        else -> throw ProtocolException("Unknown message type '${envelope.type}'")
    }
}

fun encodeOutbound(message: Outbound): String {
    val payload = when (message) {
        is Outbound.Pong -> ProtocolJson.encodeToJsonElement(message)
        is Outbound.Theme -> ProtocolJson.encodeToJsonElement(message)
        is Outbound.Graph -> ProtocolJson.encodeToJsonElement(message)
        is Outbound.NodesAdded -> ProtocolJson.encodeToJsonElement(message)
        is Outbound.FocusNode -> ProtocolJson.encodeToJsonElement(message)
        is Outbound.Relations -> ProtocolJson.encodeToJsonElement(message)
        is Outbound.NodesUpdated -> ProtocolJson.encodeToJsonElement(message)
        is Outbound.Command -> ProtocolJson.encodeToJsonElement(message)
    }
    return ProtocolJson.encodeToString(Envelope(PROTOCOL_VERSION, message.type, payload))
}
