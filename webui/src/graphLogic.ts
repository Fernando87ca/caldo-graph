import type { Point } from "./protocol";

/** `com.example.app.features.plan.viewmodel` → `c.e.a.f.plan.viewmodel` (keeps the last [keep] segments). */
export function abbreviatePackage(packageName: string, keep = 2): string {
  const parts = packageName.split(".").filter(Boolean);
  if (parts.length <= keep) return parts.join(".");
  return [...parts.slice(0, -keep).map((p) => p[0]), ...parts.slice(-keep)].join(".");
}

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

const GAP = 24;

function overlaps(a: Box, b: Box): boolean {
  return a.x < b.x + b.width + GAP && b.x < a.x + a.width + GAP && a.y < b.y + b.height + GAP && b.y < a.y + a.height + GAP;
}

/**
 * Places boxes of the given sizes starting at [anchor], stacking them downwards and moving each one
 * further down until it does not overlap any existing or already placed box.
 */
export function placeWithoutOverlap(anchor: Point, sizes: { width: number; height: number }[], existing: Box[]): Point[] {
  const occupied = [...existing];
  let y = anchor.y;
  return sizes.map((size) => {
    const box: Box = { x: anchor.x, y, ...size };
    for (let guard = 0; guard < 500; guard++) {
      const hit = occupied.find((o) => overlaps(box, o));
      if (!hit) break;
      box.y = hit.y + hit.height + GAP;
    }
    occupied.push(box);
    y = box.y + box.height + GAP;
    return { x: box.x, y: box.y };
  });
}

const CLICK_TOLERANCE = 5;

/** A right-button press and release close together is a click (opens the search); further apart it was a pan. */
export function isClick(down: Point, up: Point): boolean {
  return Math.hypot(up.x - down.x, up.y - down.y) <= CLICK_TOLERANCE;
}

/** Elements over the canvas where a right-click must not open the search. */
export const NOT_CANVAS = ".react-flow__node, .react-flow__edge, .react-flow__controls, .cg-toolbar, .cg-legend, .cg-edge-label";

/**
 * Handle pair for an edge between two boxes: vertical (bottom → top or top → bottom) when the boxes share a column
 * (left → left when [others] lie in between), otherwise from the side of the source that faces the target.
 */
export function handlesFor(source: Box, target: Box, others: Box[] = []): { sourceHandle: string; targetHandle: string } {
  const overlapX = Math.min(source.x + source.width, target.x + target.width) - Math.max(source.x, target.x);
  if (overlapX > Math.min(source.width, target.width) / 2) {
    // With boxes in between, a straight vertical edge would cross them: arc along the left side instead.
    const top = Math.min(source.y + source.height, target.y + target.height);
    const bottom = Math.max(source.y, target.y);
    const blocked = others.some((o) => o.y < bottom && o.y + o.height > top && o.x < source.x + source.width && o.x + o.width > source.x);
    if (blocked) return { sourceHandle: "s-left", targetHandle: "t-left" };
    return source.y <= target.y
      ? { sourceHandle: "s-bottom", targetHandle: "t-top" }
      : { sourceHandle: "s-top", targetHandle: "t-bottom" };
  }
  return source.x <= target.x
    ? { sourceHandle: "s-right", targetHandle: "t-left" }
    : { sourceHandle: "s-left", targetHandle: "t-right" };
}

/** Simple name and package of a node id (`class:a.b.Foo`, `fun:a.b.Bar(Int)@/path`), for nodes not loaded yet. */
export function nameOfId(id: string): { name: string; packageName: string } {
  const fqName = id.startsWith("fun:") ? id.slice(4).split("(")[0] : id.replace(/^class:/, "");
  const dot = fqName.lastIndexOf(".");
  return dot < 0 ? { name: fqName, packageName: "" } : { name: fqName.slice(dot + 1), packageName: fqName.slice(0, dot) };
}
