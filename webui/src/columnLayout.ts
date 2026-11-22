import type { GraphNode, NodeKind, Point, Relation, Side } from "./protocol";

/**
 * Horizontal position follows clean-architecture depth: UI on the left, data sources on the right.
 * Kinds without a depth (models, states, events, other helpers) have no column of their own: they sit below the
 * class they belong to.
 */
const DEPTH: Partial<Record<NodeKind, number>> = {
  composableRouter: 0,
  composablePage: 1,
  composable: 2,
  viewModel: 3,
  navigator: 4,
  useCase: 4,
  repositoryInterface: 5,
  repositoryImpl: 6,
  dataSource: 7,
};

export const isHelper = (kind: NodeKind) => DEPTH[kind] === undefined;

/**
 * Where a node added from [parent] goes; same-depth nodes follow the side of the "+" button that was used.
 * A helper injected into the parent is a dependency like any other, so it goes right instead of below.
 */
export function sideFor(parent: GraphNode, target: GraphNode, buttonSide: Side, injectedIntoParent = false): Side | "below" {
  const parentDepth = DEPTH[parent.kind];
  const targetDepth = DEPTH[target.kind];
  if (targetDepth === undefined) return injectedIntoParent && parentDepth !== undefined ? "right" : "below";
  if (parentDepth === undefined || targetDepth === parentDepth) return buttonSide;
  return targetDepth > parentDepth ? "right" : "left";
}

export interface LayoutInput {
  id: string;
  node: GraphNode;
  width: number;
  height: number;
  /** Node it was expanded from. */
  parent: string | null;
  /** Current position, used to keep the relative order of unrelated nodes. */
  position: Point;
}

export const COLUMN_GAP = 160;
export const ROW_GAP = 40;

const STRUCTURAL = new Set<Relation["kind"]>(["composition", "aggregation", "injection"]);

/**
 * Column layout: one column per depth, rows aligned with connected nodes on the left, helpers below their owner.
 * Helpers injected into a non-helper are dependencies: they go in the column right after the class that injects them.
 */
export function columnLayout(nodes: LayoutInput[], relations: Relation[]): Map<string, Point> {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const shown = relations.filter((r) => byId.has(r.source) && byId.has(r.target));
  const neighbours = (id: string) => shown.flatMap((r) => (r.source === id ? [r.target] : r.target === id ? [r.source] : []));

  // Helper id → the non-helper that injects it (shallowest one if several).
  const injector = new Map<string, LayoutInput>();
  for (const r of shown) {
    const source = byId.get(r.source)!;
    const target = byId.get(r.target)!;
    if (r.kind !== "injection" || isHelper(source.node.kind) || !isHelper(target.node.kind)) continue;
    const current = injector.get(target.id);
    if (!current || DEPTH[source.node.kind]! < DEPTH[current.node.kind]!) injector.set(target.id, source);
  }
  const isPlacedAsHelper = (n: LayoutInput) => isHelper(n.node.kind) && !injector.has(n.id);

  const columnOf = (n: LayoutInput): number => {
    const injectedBy = injector.get(n.id);
    if (injectedBy) return columnOf(injectedBy) + 1;
    const depth = DEPTH[n.node.kind]!;
    const implementsShownInterface = shown.some(
      (r) => r.kind === "implementation" && r.source === n.id && byId.get(r.target)?.node.kind === n.node.kind,
    );
    return depth + (implementsShownInterface ? 0.5 : 0);
  };

  // The non-helper a helper belongs to: the node it was expanded from, else whoever holds it, else any neighbour.
  const anchorOf = (id: string, visited = new Set<string>()): string | null => {
    const n = byId.get(id);
    if (!n || visited.has(id)) return null;
    visited.add(id);
    if (!isPlacedAsHelper(n)) return id;
    const candidates = [
      ...(n.parent ? [n.parent] : []),
      ...shown.filter((r) => r.target === id && STRUCTURAL.has(r.kind)).map((r) => r.source),
      ...neighbours(id),
    ];
    for (const candidate of candidates) {
      const anchor = anchorOf(candidate, visited);
      if (anchor) return anchor;
    }
    return null;
  };

  const mains = nodes.filter((n) => !isPlacedAsHelper(n));
  const helpers = nodes.filter(isPlacedAsHelper);
  const anchors = new Map(helpers.map((h) => [h.id, anchorOf(h.id)]));
  const firstColumn = Math.min(0, ...mains.map(columnOf));
  const columnKey = (n: LayoutInput) => {
    if (!isPlacedAsHelper(n)) return columnOf(n);
    const anchor = anchors.get(n.id);
    return anchor ? columnOf(byId.get(anchor)!) : firstColumn;
  };

  const columns = new Map<number, LayoutInput[]>();
  for (const n of nodes) columns.set(columnKey(n), [...(columns.get(columnKey(n)) ?? []), n]);

  const result = new Map<string, Point>();
  const center = (id: string) => {
    const p = result.get(id)!;
    return p.y + byId.get(id)!.height / 2;
  };

  let x = 0;
  for (const key of [...columns.keys()].sort((a, b) => a - b)) {
    const members = columns.get(key)!;
    const columnMains = members.filter((n) => !isPlacedAsHelper(n));
    const columnHelpers = members.filter(isPlacedAsHelper);

    // Main nodes: as close as possible to the average height of their already placed neighbours.
    const desired = new Map(
      columnMains.map((n) => {
        const placed = neighbours(n.id).filter((id) => result.has(id));
        const y = placed.length > 0 ? placed.reduce((sum, id) => sum + center(id), 0) / placed.length - n.height / 2 : null;
        return [n.id, y] as const;
      }),
    );
    const ordered = [...columnMains].sort(
      (a, b) => (desired.get(a.id) ?? a.position.y) - (desired.get(b.id) ?? b.position.y),
    );
    let cursor = -Infinity;
    for (const n of ordered) {
      const y = Math.max(desired.get(n.id) ?? (cursor === -Infinity ? 0 : cursor), cursor);
      result.set(n.id, { x, y });
      cursor = y + n.height + ROW_GAP;
    }

    // Helpers: below the main nodes, grouped by the node they belong to (in that node's vertical order).
    const anchorY = (h: LayoutInput) => {
      const anchor = anchors.get(h.id);
      return anchor && result.has(anchor) ? result.get(anchor)!.y : Infinity;
    };
    const orderedHelpers = [...columnHelpers].sort(
      (a, b) => anchorY(a) - anchorY(b) || a.position.y - b.position.y || a.node.name.localeCompare(b.node.name),
    );
    if (cursor === -Infinity) cursor = 0;
    for (const h of orderedHelpers) {
      result.set(h.id, { x, y: cursor });
      cursor += h.height + ROW_GAP;
    }

    x += Math.max(...members.map((n) => n.width)) + COLUMN_GAP;
  }
  return result;
}
