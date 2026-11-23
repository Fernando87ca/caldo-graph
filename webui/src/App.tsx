import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Background,
  ControlButton,
  Controls,
  SelectionMode,
  ReactFlow,
  ViewportPortal,
  useNodesState,
  useReactFlow,
  type EdgeTypes,
  type NodeChangeset,
  type NodeTypes,
} from "@xyflow/react";
import { isEmbedded, send, subscribe } from "./bridge";
import { ClassNode, type ClassFlowNode } from "./ClassNode";
import { ExpandPicker, type ExpandRequest } from "./ExpandPicker";
import { FiltersPanel } from "./FiltersPanel";
import { GraphActionsContext, GraphInfoContext, type GraphActions, type GraphInfo } from "./GraphActions";
import { handlesFor, isClick, NOT_CANVAS, placeWithoutOverlap, type Box } from "./graphLogic";
import { COLUMN_GAP, columnLayout, sideFor } from "./columnLayout";
import { Legend } from "./Legend";
import { envelope, type NodeKind, type PlacedNode, type Point, type RelationKind, type Side } from "./protocol";
import { mergeRelations, pendingFor, relationKey, relationKindsWith, visibleRelations, withExpanded, type RelationIndex } from "./relations";
import { exportImage } from "./exportImage";
import { applyTheme } from "./theme";
import { UmlEdge, UmlMarkers, type UmlFlowEdge } from "./UmlEdge";

const nodeTypes: NodeTypes = { class: ClassNode };
const edgeTypes: EdgeTypes = { uml: UmlEdge };
const SNAPSHOT_DELAY_MS = 300;
const DEFAULT_WIDTH = 280;

function toFlowNode(placed: PlacedNode, position: Point): ClassFlowNode {
  return {
    id: placed.node.id,
    type: "class",
    position,
    data: { node: placed.node, collapsed: placed.collapsed, parent: placed.parent },
  };
}

/** Rough size before React Flow has measured a node, used only to avoid overlaps when placing. */
function estimatedSize(placed: PlacedNode) {
  const n = placed.node;
  const lines = n.properties.length + n.functions.length + n.parameters.length;
  return { width: DEFAULT_WIDTH, height: 70 + lines * 20 + 24 * 2 };
}

/** Size of the rendered box in flow units (offset sizes are not affected by the zoom transform). */
function renderedSize(id: string): { width: number; height: number } | undefined {
  const element = document.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`);
  return element && element.offsetWidth > 0 ? { width: element.offsetWidth, height: element.offsetHeight } : undefined;
}

/** [measured] comes from React Flow's internal node; user-facing nodes may not carry their measured size. */
function boxOf(node: ClassFlowNode, measured?: { width?: number; height?: number }): Box {
  return {
    x: node.position.x,
    y: node.position.y,
    width: measured?.width ?? node.measured?.width ?? DEFAULT_WIDTH,
    height: measured?.height ?? node.measured?.height ?? 120,
  };
}

export function App() {
  const [nodes, setNodes, onNodesChangeBase] = useNodesState<ClassFlowNode>([]);
  const [relations, setRelations] = useState<RelationIndex>(new Map());
  // Read from message handlers without re-subscribing to the bridge (which would send "ready" again).
  const relationsRef = useRef(relations);
  relationsRef.current = relations;
  const [expandRequest, setExpandRequest] = useState<ExpandRequest | null>(null);
  const [hiddenRelations, setHiddenRelations] = useState<Set<RelationKind>>(new Set());
  const [hiddenNodes, setHiddenNodes] = useState<Set<NodeKind>>(new Set());
  const [legendVisible, setLegendVisible] = useState(true);
  const [status, setStatus] = useState<string | null>("Waiting for IDE…");
  /** "pan": dragging the canvas moves it; "select": dragging draws a selection box (pan with the right/middle button). */
  const [mouseMode, setMouseMode] = useState<"pan" | "select">("pan");
  const hoveredNode = useRef<string | null>(null);
  const { screenToFlowPosition, setCenter, fitView, getNodes, getInternalNode } = useReactFlow<ClassFlowNode, UmlFlowEdge>();
  const box = useCallback((n: ClassFlowNode) => boxOf(n, renderedSize(n.id) ?? getInternalNode(n.id)?.measured), [getInternalNode]);

  // Fit the view once React Flow has measured the nodes: the whole graph after a restore (true),
  // or the expanded node and its new neighbours after an expansion (their ids).
  const [fitPending, setFitPending] = useState<boolean | string[]>(false);
  useEffect(() => {
    if (fitPending === false) return;
    let attempts = 0;
    const timer = window.setInterval(() => {
      const all = getNodes();
      const targets = fitPending === true ? all : all.filter((n) => fitPending.includes(n.id));
      const measured = targets.length > 0 && targets.every((n) => renderedSize(n.id) ?? getInternalNode(n.id)?.measured?.width);
      if (!measured && ++attempts < 40) return;
      window.clearInterval(timer);
      if (fitPending === true) fitView({ padding: 0.2, maxZoom: 1 });
      else fitView({ nodes: targets.map((n) => ({ id: n.id })), padding: 0.2, maxZoom: 1, duration: 300 });
      setFitPending(false);
    }, 50);
    return () => window.clearInterval(timer);
  }, [fitPending, getNodes, getInternalNode, fitView]);

  // Snapshot of the user-editable state, sent to Kotlin for persistence.
  const settingsRef = useRef({ legendVisible, hiddenRelations, hiddenNodes });
  settingsRef.current = { legendVisible, hiddenRelations, hiddenNodes };
  const snapshotTimer = useRef<number | undefined>(undefined);
  const scheduleSnapshot = useCallback(() => {
    window.clearTimeout(snapshotTimer.current);
    snapshotTimer.current = window.setTimeout(() => {
      const { legendVisible, hiddenRelations, hiddenNodes } = settingsRef.current;
      const layout = getNodes().map((n) => ({
        id: n.id,
        x: n.position.x,
        y: n.position.y,
        collapsed: n.data.collapsed,
        parent: n.data.parent,
      }));
      send(
        envelope("graphChanged", {
          nodes: layout,
          legendVisible,
          filters: { hiddenRelationKinds: [...hiddenRelations], hiddenNodeKinds: [...hiddenNodes] },
        }),
      );
    }, SNAPSHOT_DELAY_MS);
  }, [getNodes]);

  const onNodesChange = useCallback(
    (changes: NodeChangeset<ClassFlowNode>) => {
      onNodesChangeBase(changes);
      if (changes.toArray().some((c) => c.type === "remove" || (c.type === "position" && c.dragging === false))) scheduleSnapshot();
    },
    [onNodesChangeBase, scheduleSnapshot],
  );

  // Nodes hidden by the layer filter stay in the graph (and in the snapshot) but are not drawn.
  const shownNodes = useMemo(
    () => nodes.map((n) => (hiddenNodes.has(n.data.node.kind) === !!n.hidden ? n : { ...n, hidden: hiddenNodes.has(n.data.node.kind) })),
    [nodes, hiddenNodes],
  );
  const presentIds = useMemo(() => new Set(nodes.map((n) => n.id)), [nodes]);
  const visibleIds = useMemo(() => new Set(shownNodes.filter((n) => !n.hidden).map((n) => n.id)), [shownNodes]);

  const edges: UmlFlowEdge[] = useMemo(() => {
    const boxes = new Map(nodes.map((n) => [n.id, box(n)]));
    return visibleRelations(relations, visibleIds, hiddenRelations).map((relation) => ({
      id: relationKey(relation),
      type: "uml",
      source: relation.source,
      target: relation.target,
      ...handlesFor(
        boxes.get(relation.source)!,
        boxes.get(relation.target)!,
        [...boxes].filter(([id]) => id !== relation.source && id !== relation.target && visibleIds.has(id)).map(([, b]) => b),
      ),
      data: { relation },
    }));
  }, [nodes, relations, visibleIds, hiddenRelations, box]);

  const info: GraphInfo = useMemo(() => {
    const pending = new Map(nodes.map((n) => [n.id, pendingFor(n.id, relations, presentIds, n.data.node.isInterface)]));
    const expandedChildren = new Map<string, number>();
    for (const n of nodes) {
      if (n.data.parent) expandedChildren.set(n.data.parent, (expandedChildren.get(n.data.parent) ?? 0) + 1);
    }
    return { pending, expandedChildren };
  }, [nodes, relations, presentIds]);

  /** Removes the selected boxes, or the box under the mouse when nothing is selected. */
  const deleteSelectionOrHovered = useCallback(() => {
    const all = getNodes();
    const selected = all.filter((n) => n.selected && !n.hidden).map((n) => n.id);
    const targets = new Set(selected.length > 0 ? selected : hoveredNode.current ? [hoveredNode.current] : []);
    if (targets.size === 0) return;
    hoveredNode.current = null;
    setNodes((current) =>
      current
        .filter((n) => !targets.has(n.id))
        .map((n) => (n.data.parent && targets.has(n.data.parent) ? { ...n, data: { ...n.data, parent: null } } : n)),
    );
    scheduleSnapshot();
  }, [getNodes, setNodes, scheduleSnapshot]);
  const deleteRef = useRef(deleteSelectionOrHovered);
  deleteRef.current = deleteSelectionOrHovered;

  // Outside the IDE (or if the IDE lets the key through) the web app handles the keys itself.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Delete" && e.key !== "Backspace") return;
      if (e.target instanceof HTMLElement && e.target.closest("input, textarea, [contenteditable]")) return;
      e.preventDefault();
      deleteRef.current();
    };
    // Right-click on the empty canvas (without dragging, which pans in selection mode) opens the search there.
    let rightDown: Point | null = null;
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target instanceof Element ? e.target : null;
      rightDown = e.button === 2 && target?.closest(".react-flow") && !target.closest(NOT_CANVAS) ? { x: e.clientX, y: e.clientY } : null;
    };
    const onPointerUp = (e: PointerEvent) => {
      if (e.button !== 2 || !rightDown) return;
      if (isClick(rightDown, { x: e.clientX, y: e.clientY })) send(envelope("openSearch", { x: e.clientX, y: e.clientY }));
      rightDown = null;
    };
    window.addEventListener("pointerdown", onPointerDown, true);
    window.addEventListener("pointerup", onPointerUp, true);

    // The browser's context menu would pop up when panning with the right button; Shift+right-click still opens it.
    const onContextMenu = (e: MouseEvent) => {
      if (!e.shiftKey) e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("contextmenu", onContextMenu);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("contextmenu", onContextMenu);
      window.removeEventListener("pointerdown", onPointerDown, true);
      window.removeEventListener("pointerup", onPointerUp, true);
    };
  }, []);

  const autoLayout = useCallback(() => {
    const current = getNodes().filter((n) => !n.hidden);
    const shownRelations = visibleRelations(relations, new Set(current.map((n) => n.id)), new Set());
    const positions = columnLayout(
      current.map((n) => ({
        id: n.id,
        node: n.data.node,
        width: box(n).width,
        height: box(n).height,
        parent: n.data.parent,
        position: n.position,
      })),
      shownRelations,
    );
    setNodes((all) => all.map((n) => (positions.has(n.id) ? { ...n, position: positions.get(n.id)! } : n)));
    window.setTimeout(() => fitView({ padding: 0.2, maxZoom: 1, duration: 300 }), 50);
    scheduleSnapshot();
  }, [getNodes, relations, setNodes, fitView, scheduleSnapshot, box]);
  const autoLayoutRef = useRef(autoLayout);
  autoLayoutRef.current = autoLayout;

  const actions: GraphActions = useMemo(
    () => ({
      navigate: (id, member) => send(envelope("navigate", { id, member })),
      remove: (id, expanded) => {
        setNodes((current) => {
          const removed = expanded ? withExpanded(id, new Map(current.map((n) => [n.id, n.data.parent]))) : new Set([id]);
          return current
            .filter((n) => !removed.has(n.id))
            .map((n) => (n.data.parent && removed.has(n.data.parent) ? { ...n, data: { ...n.data, parent: null } } : n));
        });
        scheduleSnapshot();
      },
      toggleSection: (id, section) => {
        setNodes((current) =>
          current.map((n) => {
            if (n.id !== id) return n;
            const collapsed = n.data.collapsed.includes(section)
              ? n.data.collapsed.filter((s) => s !== section)
              : [...n.data.collapsed, section];
            return { ...n, data: { ...n.data, collapsed } };
          }),
        );
        scheduleSnapshot();
      },
      // Ask which neighbours to add first; the picker sends "expand" with the chosen ones.
      expand: (id, side, targets) =>
        setExpandRequest({ id, side, targets, kinds: relationKindsWith(id, targets, relationsRef.current) }),
      findIncoming: (id) => send(envelope("findIncoming", { id })),
    }),
    [setNodes, scheduleSnapshot],
  );

  /** Positions for new nodes: beside [near] when expanding, otherwise below the anchor point. */
  const placeNew = useCallback(
    (added: PlacedNode[], current: ClassFlowNode[], anchor: Point | null, near: { id: string; side: Side } | null): Point[] => {
      const sizes = added.map(estimatedSize);
      const occupied = current.map(box);
      const parent = near && current.find((n) => n.id === near.id);
      if (parent) {
        // Deeper layers to the right, shallower to the left, helpers (models, states…) below the parent.
        const parentBox = box(parent);
        const positions = new Array<Point>(added.length);
        const groups = new Map<Side | "below", number[]>();
        const injected = new Set(
          (relationsRef.current.get(parent.id)?.outgoing ?? []).filter((r) => r.kind === "injection").map((r) => r.target),
        );
        added.forEach((p, i) => {
          const side = sideFor(parent.data.node, p.node, near.side, injected.has(p.node.id));
          groups.set(side, [...(groups.get(side) ?? []), i]);
        });
        for (const [side, indexes] of groups) {
          const groupSizes = indexes.map((i) => sizes[i]);
          const total = groupSizes.reduce((sum, s) => sum + s.height + 24, -24);
          const start =
            side === "below"
              ? { x: parentBox.x, y: parentBox.y + parentBox.height + 40 }
              : {
                  x: side === "right" ? parentBox.x + parentBox.width + COLUMN_GAP : parentBox.x - COLUMN_GAP - DEFAULT_WIDTH,
                  y: parentBox.y + parentBox.height / 2 - total / 2,
                };
          const placed = placeWithoutOverlap(start, groupSizes, occupied);
          indexes.forEach((index, k) => {
            positions[index] = placed[k];
            occupied.push({ ...placed[k], ...groupSizes[k] });
          });
        }
        return positions;
      }
      const viewportCenter = { x: window.innerWidth / 2, y: window.innerHeight / 3 };
      return placeWithoutOverlap(screenToFlowPosition(anchor ?? viewportCenter), sizes, occupied);
    },
    [screenToFlowPosition, box],
  );

  useEffect(() => {
    const unsubscribe = subscribe((message) => {
      switch (message.type) {
        case "pong":
          setStatus(null);
          break;
        case "theme":
          document.documentElement.dataset.theme = message.payload.dark ? "dark" : "light";
          applyTheme(message.payload.colors);
          break;
        case "graph": {
          const restored = message.payload.nodes.map((p) => toFlowNode(p, p.position ?? { x: 0, y: 0 }));
          setNodes(restored);
          setRelations(new Map());
          setLegendVisible(message.payload.legendVisible);
          setHiddenRelations(new Set(message.payload.filters.hiddenRelationKinds));
          setHiddenNodes(new Set(message.payload.filters.hiddenNodeKinds));
          setFitPending(restored.length > 0);
          break;
        }
        case "nodesAdded": {
          const { nodes: added, anchor, near } = message.payload;
          setNodes((current) => {
            const positions = placeNew(added, current, anchor, near);
            return [
              ...current.map((n) => ({ ...n, selected: false })),
              ...added.map((p, i) => ({ ...toFlowNode(p, positions[i]), selected: true })),
            ];
          });
          if (near) setFitPending((pending) => [...(Array.isArray(pending) ? pending : []), near.id, ...added.map((p) => p.node.id)]);
          scheduleSnapshot();
          break;
        }
        case "relations":
          setRelations((current) => mergeRelations(current, message.payload.items));
          break;
        case "focusNode": {
          const node = getNodes().find((n) => n.id === message.payload.id);
          if (!node) break;
          setNodes((current) => current.map((n) => ({ ...n, selected: n.id === node.id })));
          const nodeBox = box(node);
          setCenter(nodeBox.x + nodeBox.width / 2, nodeBox.y + nodeBox.height / 2, { zoom: 1, duration: 300 });
          break;
        }
        case "nodesUpdated": {
          const updated = new Map(message.payload.nodes.map((n) => [n.id, n]));
          setNodes((current) => current.map((n) => (updated.has(n.id) ? { ...n, data: { ...n.data, node: updated.get(n.id)! } } : n)));
          break;
        }
        case "command": {
          const name = message.payload.name;
          if (name === "deleteSelection") deleteRef.current();
          else if (name === "autoLayout") void autoLayoutRef.current();
          else if (name === "fitView") fitView({ padding: 0.2, maxZoom: 1, duration: 300 });
          else {
            const format = name === "exportPng" ? "png" : "svg";
            exportImage(format, getNodes().filter((n) => !n.hidden))
              .then((dataUrl) => send(envelope("exportImage", { format, dataUrl })))
              .catch((e: unknown) => send(envelope("log", { level: "warn", message: `Export failed: ${String(e)}` })));
          }
          break;
        }
      }
    });
    // The IDE binds Delete/Backspace to "delete boxes"; tell it when a text field needs those keys instead.
    const isTextField = (el: EventTarget | null) =>
      el instanceof HTMLElement && (el.isContentEditable || el instanceof HTMLTextAreaElement ||
        (el instanceof HTMLInputElement && !["checkbox", "radio", "button", "submit"].includes(el.type)));
    const onFocusIn = (e: FocusEvent) => send(envelope("textFocus", { active: isTextField(e.target) }));
    const onFocusOut = (e: FocusEvent) => {
      if (isTextField(e.target) && !isTextField(e.relatedTarget)) send(envelope("textFocus", { active: false }));
    };
    document.addEventListener("focusin", onFocusIn);
    document.addEventListener("focusout", onFocusOut);
    send(envelope("ready", {}));
    send(envelope("ping", { nonce: Date.now() }));
    if (!isEmbedded()) setStatus("Standalone mode (no IDE bridge)");
    return () => {
      unsubscribe();
      document.removeEventListener("focusin", onFocusIn);
      document.removeEventListener("focusout", onFocusOut);
    };
  }, [setNodes, setCenter, fitView, getNodes, scheduleSnapshot, placeNew]);

  const toggle = <T,>(set: Set<T>, value: T) => {
    const next = new Set(set);
    if (!next.delete(value)) next.add(value);
    return next;
  };

  return (
    <GraphActionsContext.Provider value={actions}>
      <GraphInfoContext.Provider value={info}>
        {status && <div className="cg-status">{status}</div>}
        {expandRequest && (
          <ExpandPicker
            request={expandRequest}
            onAccept={(targets) => {
              send(envelope("expand", { id: expandRequest.id, side: expandRequest.side, targets }));
              setExpandRequest(null);
            }}
            onCancel={() => setExpandRequest(null)}
          />
        )}
        {nodes.length === 0 && !status && (
          <div className="cg-empty">Drag classes or composables here, right-click to search, or use “Add to Caldo Graph” (⌃⌥⇧G).</div>
        )}
        <FiltersPanel
          hiddenRelations={hiddenRelations}
          hiddenNodes={hiddenNodes}
          onToggleRelation={(kind) => {
            setHiddenRelations((s) => toggle(s, kind));
            scheduleSnapshot();
          }}
          onToggleNode={(kind) => {
            setHiddenNodes((s) => toggle(s, kind));
            scheduleSnapshot();
          }}
          onAutoLayout={() => void autoLayout()}
        />
        <Legend
          visible={legendVisible}
          onToggle={() => {
            setLegendVisible((v) => !v);
            scheduleSnapshot();
          }}
        />
        <ReactFlow
          nodes={shownNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          deleteKeyCode={null}
          onNodeMouseEnter={(_, node) => (hoveredNode.current = node.id)}
          onNodeMouseLeave={(_, node) => {
            if (hoveredNode.current === node.id) hoveredNode.current = null;
          }}
          panOnDrag={mouseMode === "pan" ? true : [1, 2]}
          selectionOnDrag={mouseMode === "select"}
          selectionMode={SelectionMode.Partial}
          minZoom={0.1}
          proOptions={{ hideAttribution: true }}
        >
          {/* Inside the viewport so exported images keep the edge markers. */}
          <ViewportPortal>
            <UmlMarkers />
          </ViewportPortal>
          <Background />
          <Controls showInteractive={false}>
            <ControlButton
              className={mouseMode === "pan" ? "is-active" : ""}
              title="Free navigation: drag to move the canvas"
              onClick={() => setMouseMode("pan")}
            >
              ✋
            </ControlButton>
            <ControlButton
              className={mouseMode === "select" ? "is-active" : ""}
              title="Selection: drag to select several boxes (right or middle button moves the canvas)"
              onClick={() => setMouseMode("select")}
            >
              ⬚
            </ControlButton>
          </Controls>
        </ReactFlow>
      </GraphInfoContext.Provider>
    </GraphActionsContext.Provider>
  );
}
