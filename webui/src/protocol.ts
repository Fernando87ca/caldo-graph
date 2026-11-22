// Mirror of bridge/Protocol.kt and model/Graph.kt. Bump PROTOCOL_VERSION on incompatible changes on both sides.
export const PROTOCOL_VERSION = 1;

export interface Envelope<T extends string = string, P = unknown> {
  v: number;
  type: T;
  payload: P;
}

export type NodeKind =
  | "composablePage"
  | "composableRouter"
  | "composable"
  | "navigator"
  | "viewModel"
  | "useCase"
  | "repositoryInterface"
  | "repositoryImpl"
  | "dataSource"
  | "model"
  | "other";

export interface Member {
  name: string;
  kind: "property" | "function" | "parameter";
  signature: string;
}

export interface GraphNode {
  id: string;
  kind: NodeKind;
  name: string;
  packageName: string;
  isInterface: boolean;
  properties: Member[];
  functions: Member[];
  parameters: Member[];
  notFound: boolean;
}

export interface Point {
  x: number;
  y: number;
}

export type RelationKind =
  | "inheritance"
  | "implementation"
  | "injection"
  | "composition"
  | "aggregation"
  | "dependency"
  | "obtains"
  | "calls"
  | "renders";

/** `source` is the declaration whose code mentions `target`. */
export interface Relation {
  source: string;
  target: string;
  kind: RelationKind;
  label: string | null;
  multiplicity: string | null;
}

/** A null list means "unchanged / not known yet". */
export interface NodeRelations {
  id: string;
  outgoing: Relation[] | null;
  incoming: Relation[] | null;
}

export interface PlacedNode {
  node: GraphNode;
  position: Point | null;
  collapsed: string[];
  parent: string | null;
}

export interface NodeLayout {
  id: string;
  x: number;
  y: number;
  collapsed: string[];
  parent: string | null;
}

export interface Filters {
  hiddenRelationKinds: RelationKind[];
  hiddenNodeKinds: NodeKind[];
}

export type Side = "left" | "right";

/** Messages sent from Kotlin to the web app. */
export type InboundMessage =
  | Envelope<"pong", { nonce: number; ideVersion: string }>
  | Envelope<"theme", { dark: boolean; colors: Record<string, string> }>
  | Envelope<"graph", { nodes: PlacedNode[]; legendVisible: boolean; filters: Filters }>
  | Envelope<"nodesAdded", { nodes: PlacedNode[]; anchor: Point | null; near: { id: string; side: Side } | null }>
  | Envelope<"relations", { items: NodeRelations[] }>
  | Envelope<"focusNode", { id: string }>
  | Envelope<"command", { name: "autoLayout" | "fitView" | "exportPng" | "exportSvg" | "deleteSelection" }>
  | Envelope<"nodesUpdated", { nodes: GraphNode[] }>;

/** Messages sent from the web app to Kotlin. */
export type OutboundMessage =
  | Envelope<"ready", Record<string, never>>
  | Envelope<"ping", { nonce: number }>
  | Envelope<"log", { level: "info" | "warn" | "error"; message: string }>
  | Envelope<"navigate", { id: string; member: string | null }>
  | Envelope<"graphChanged", { nodes: NodeLayout[]; legendVisible: boolean; filters: Filters }>
  | Envelope<"expand", { id: string; side: Side; targets: string[] }>
  | Envelope<"findIncoming", { id: string }>
  | Envelope<"exportImage", { format: "png" | "svg"; dataUrl: string }>
  | Envelope<"openSearch", Point>;

type PayloadOf<T extends OutboundMessage["type"]> = Extract<OutboundMessage, { type: T }>["payload"];

export function envelope<T extends OutboundMessage["type"]>(type: T, payload: PayloadOf<T>): OutboundMessage {
  return { v: PROTOCOL_VERSION, type, payload } as OutboundMessage;
}

export function parseInbound(raw: unknown): InboundMessage | null {
  const value = typeof raw === "string" ? safeJson(raw) : raw;
  if (!value || typeof value !== "object") return null;
  const msg = value as Partial<Envelope>;
  if (msg.v !== PROTOCOL_VERSION || typeof msg.type !== "string" || msg.payload === undefined) return null;
  return msg as InboundMessage;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
