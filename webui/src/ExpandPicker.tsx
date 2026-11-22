import { useEffect, useState } from "react";
import { abbreviatePackage, nameOfId } from "./graphLogic";
import type { RelationKind, Side } from "./protocol";
import { RELATION_LABELS } from "./UmlEdge";

export interface ExpandRequest {
  id: string;
  side: Side;
  targets: string[];
  kinds: Map<string, RelationKind[]>;
}

interface Props {
  request: ExpandRequest;
  onAccept(targets: string[]): void;
  onCancel(): void;
}

/** Lets the user choose which of the pending neighbours of a node to add before expanding it. */
export function ExpandPicker({ request, onAccept, onCancel }: Props) {
  const [checked, setChecked] = useState<Set<string>>(() => new Set(request.targets));
  const all = checked.size === request.targets.length;
  const accept = () => checked.size > 0 && onAccept(request.targets.filter((t) => checked.has(t)));
  const items = request.targets
    .map((id) => ({ id, ...nameOfId(id) }))
    .sort((a, b) => a.name.localeCompare(b.name));

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  return (
    <div className="cg-modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <form
        className="cg-modal"
        role="dialog"
        aria-label="Choose what to add"
        onSubmit={(e) => {
          e.preventDefault();
          accept();
        }}
      >
        <header className="cg-modal-title">
          {request.side === "left" ? "Add incoming" : "Add outgoing"} · {nameOfId(request.id).name}
        </header>
        <label className="cg-pick-all">
          <input
            type="checkbox"
            checked={all}
            ref={(el) => {
              if (el) el.indeterminate = !all && checked.size > 0;
            }}
            onChange={() => setChecked(all ? new Set() : new Set(request.targets))}
          />
          Select all ({checked.size}/{request.targets.length})
        </label>
        <ul className="cg-pick-list">
          {items.map((item) => (
            <li key={item.id}>
              <label title={item.id}>
                <input
                  type="checkbox"
                  checked={checked.has(item.id)}
                  onChange={() =>
                    setChecked((s) => {
                      const next = new Set(s);
                      if (!next.delete(item.id)) next.add(item.id);
                      return next;
                    })
                  }
                />
                <span className="cg-pick-name">{item.name}</span>
                <span className="cg-pick-meta">
                  {[...(request.kinds.get(item.id) ?? []).map((k) => RELATION_LABELS[k]), abbreviatePackage(item.packageName)]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </label>
            </li>
          ))}
        </ul>
        <footer className="cg-modal-actions">
          <button type="button" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" className="is-primary" disabled={checked.size === 0} autoFocus>
            Accept
          </button>
        </footer>
      </form>
    </div>
  );
}
