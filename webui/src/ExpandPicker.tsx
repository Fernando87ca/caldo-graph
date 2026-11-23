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
  const [query, setQuery] = useState("");
  const accept = () => checked.size > 0 && onAccept(request.targets.filter((t) => checked.has(t)));
  const items = request.targets
    .map((id) => {
      const { name, packageName } = nameOfId(id);
      const meta = [...(request.kinds.get(id) ?? []).map((k) => RELATION_LABELS[k]), abbreviatePackage(packageName)];
      return { id, name, packageName, meta: meta.filter(Boolean).join(" · ") };
    })
    .sort((a, b) => a.name.localeCompare(b.name));
  // Every word must appear in the name, package or relation kinds; "Select all" applies to the visible rows only.
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const visible = items.filter((item) => {
    const text = `${item.name} ${item.packageName} ${item.meta}`.toLowerCase();
    return words.every((w) => text.includes(w));
  });
  const visibleChecked = visible.filter((item) => checked.has(item.id)).length;
  const all = visible.length > 0 && visibleChecked === visible.length;

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
        <input
          className="cg-pick-filter"
          type="search"
          placeholder="Filter…"
          value={query}
          autoFocus
          onChange={(e) => setQuery(e.target.value)}
        />
        <label className="cg-pick-all">
          <input
            type="checkbox"
            checked={all}
            ref={(el) => {
              if (el) el.indeterminate = !all && visibleChecked > 0;
            }}
            disabled={visible.length === 0}
            onChange={() =>
              setChecked((s) => {
                const next = new Set(s);
                visible.forEach((item) => (all ? next.delete(item.id) : next.add(item.id)));
                return next;
              })
            }
          />
          {words.length > 0 ? `Select all shown (${visible.length})` : "Select all"} · {checked.size}/{request.targets.length} selected
        </label>
        <ul className="cg-pick-list">
          {visible.length === 0 && <li className="cg-pick-empty">No matches</li>}
          {visible.map((item) => (
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
                <span className="cg-pick-meta">{item.meta}</span>
              </label>
            </li>
          ))}
        </ul>
        <footer className="cg-modal-actions">
          <button type="button" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" className="is-primary" disabled={checked.size === 0}>
            Accept
          </button>
        </footer>
      </form>
    </div>
  );
}
