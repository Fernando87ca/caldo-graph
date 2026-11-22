import { memo } from "react";
import { BaseEdge, EdgeLabelRenderer, getBezierPath, Position, type Edge, type EdgeProps } from "@xyflow/react";
import type { Relation, RelationKind } from "./protocol";

export type UmlEdgeData = { relation: Relation };
export type UmlFlowEdge = Edge<UmlEdgeData, "uml">;

interface EdgeStyle {
  dashed: boolean;
  /** Marker at the source end (the owner) and/or the target end. */
  start?: string;
  end?: string;
  stereotype?: string;
}

export const EDGE_STYLES: Record<RelationKind, EdgeStyle> = {
  inheritance: { dashed: false, end: "cg-triangle" },
  implementation: { dashed: true, end: "cg-triangle" },
  injection: { dashed: false, start: "cg-diamond", stereotype: "inject" },
  composition: { dashed: false, start: "cg-diamond-filled" },
  aggregation: { dashed: false, start: "cg-diamond" },
  dependency: { dashed: true, end: "cg-arrow" },
  obtains: { dashed: true, end: "cg-arrow", stereotype: "obtains" },
  calls: { dashed: true, end: "cg-arrow", stereotype: "calls" },
  renders: { dashed: true, end: "cg-arrow", stereotype: "renders" },
};

export const RELATION_LABELS: Record<RelationKind, string> = {
  inheritance: "Inheritance",
  implementation: "Implementation",
  injection: "«inject»",
  composition: "Composition",
  aggregation: "Aggregation",
  dependency: "Dependency",
  obtains: "«obtains»",
  calls: "«calls»",
  renders: "«renders»",
};

// Inline styles (not CSS classes) so that image export, which does not inline SVG styles, keeps them.
const HOLLOW = { fill: "var(--cg-background)", stroke: "var(--cg-edge)", strokeWidth: 1.3 };
const FILLED = { fill: "var(--cg-edge)", stroke: "var(--cg-edge)", strokeWidth: 1.3 };
const OPEN = { fill: "none", stroke: "var(--cg-edge)", strokeWidth: 1.3 };

/** SVG markers shared by all edges; `auto-start-reverse` lets the same marker sit at either end. */
export function UmlMarkers() {
  return (
    <svg className="cg-markers" aria-hidden>
      <defs>
        <marker id="cg-triangle" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="12" markerHeight="12" orient="auto-start-reverse" markerUnits="userSpaceOnUse">
          <path d="M1,1 L11,6 L1,11 Z" style={HOLLOW} />
        </marker>
        <marker id="cg-diamond" viewBox="0 0 16 10" refX="15" refY="5" markerWidth="16" markerHeight="10" orient="auto-start-reverse" markerUnits="userSpaceOnUse">
          <path d="M1,5 L8,1 L15,5 L8,9 Z" style={HOLLOW} />
        </marker>
        <marker id="cg-diamond-filled" viewBox="0 0 16 10" refX="15" refY="5" markerWidth="16" markerHeight="10" orient="auto-start-reverse" markerUnits="userSpaceOnUse">
          <path d="M1,5 L8,1 L15,5 L8,9 Z" style={FILLED} />
        </marker>
        <marker id="cg-arrow" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="12" markerHeight="12" orient="auto-start-reverse" markerUnits="userSpaceOnUse">
          <path d="M1,1 L11,6 L1,11" style={OPEN} />
        </marker>
      </defs>
    </svg>
  );
}

/** Arc that leaves and enters on the left, bulging further out the longer it is (edges within one column). */
function sideArc(sx: number, sy: number, tx: number, ty: number): [string, number, number] {
  const bulge = Math.min(60 + Math.abs(ty - sy) * 0.15, 220);
  const x = Math.min(sx, tx) - bulge;
  const path = `M ${sx},${sy} C ${x},${sy} ${x},${ty} ${tx},${ty}`;
  return [path, Math.min(sx, tx) - bulge * 0.75, (sy + ty) / 2];
}

function UmlEdgeComponent({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data, selected }: EdgeProps<UmlFlowEdge>) {
  const relation = data!.relation;
  const style = EDGE_STYLES[relation.kind];
  const [path, labelX, labelY] =
    sourcePosition === Position.Left && targetPosition === Position.Left
      ? sideArc(sourceX, sourceY, targetX, targetY)
      : getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
  const text = [style.stereotype && `«${style.stereotype}»`, relation.label].filter(Boolean).join(" ");
  const showMultiplicity = relation.multiplicity && (relation.kind === "aggregation" || relation.multiplicity !== "1");

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        className={`cg-edge cg-edge-${relation.kind}${selected ? " is-selected" : ""}`}
        style={{
          stroke: selected ? "var(--cg-accent)" : "var(--cg-edge)",
          strokeWidth: selected ? 2 : 1.3,
          strokeDasharray: style.dashed ? "6 4" : undefined,
        }}
        markerStart={style.start ? `url(#${style.start})` : undefined}
        markerEnd={style.end ? `url(#${style.end})` : undefined}
      />
      <EdgeLabelRenderer>
        {text && (
          <div className="cg-edge-label" style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}>
            {text}
          </div>
        )}
        {showMultiplicity && (
          <div className="cg-edge-multiplicity" style={{ transform: `translate(-100%, -120%) translate(${targetX - 6}px, ${targetY}px)` }}>
            {relation.multiplicity}
          </div>
        )}
      </EdgeLabelRenderer>
    </>
  );
}

export const UmlEdge = memo(UmlEdgeComponent);
