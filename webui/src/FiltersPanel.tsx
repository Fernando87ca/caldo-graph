import { useState } from "react";
import { KIND_ORDER, KINDS } from "./kinds";
import type { NodeKind, RelationKind } from "./protocol";
import { RELATION_LABELS } from "./UmlEdge";

const RELATION_ORDER = Object.keys(RELATION_LABELS) as RelationKind[];

interface Props {
  hiddenRelations: Set<RelationKind>;
  hiddenNodes: Set<NodeKind>;
  onToggleRelation(kind: RelationKind): void;
  onToggleNode(kind: NodeKind): void;
  onAutoLayout(): void;
}

export function FiltersPanel({ hiddenRelations, hiddenNodes, onToggleRelation, onToggleNode, onAutoLayout }: Props) {
  const [open, setOpen] = useState(false);
  const active = hiddenRelations.size + hiddenNodes.size;
  return (
    <div className="cg-toolbar">
      <button onClick={onAutoLayout} title="Arrange the graph automatically">
        Auto-layout
      </button>
      <button onClick={() => setOpen((o) => !o)} className={active > 0 ? "is-active" : ""}>
        Filters{active > 0 ? ` (${active})` : ""}
      </button>
      {open && (
        <div className="cg-filters">
          <fieldset>
            <legend>Relations</legend>
            {RELATION_ORDER.map((kind) => (
              <label key={kind}>
                <input type="checkbox" checked={!hiddenRelations.has(kind)} onChange={() => onToggleRelation(kind)} />
                {RELATION_LABELS[kind]}
              </label>
            ))}
          </fieldset>
          <fieldset>
            <legend>Layers</legend>
            {KIND_ORDER.map((kind) => (
              <label key={kind}>
                <input type="checkbox" checked={!hiddenNodes.has(kind)} onChange={() => onToggleNode(kind)} />
                <span className={`cg-swatch kind-${kind}`} />
                {KINDS[kind].label}
              </label>
            ))}
          </fieldset>
        </div>
      )}
    </div>
  );
}
