import { KIND_ORDER, KINDS } from "./kinds";

export function Legend({ visible, onToggle }: { visible: boolean; onToggle: () => void }) {
  return (
    <div className="cg-legend">
      <button className="cg-legend-toggle" onClick={onToggle}>
        {visible ? "Hide legend" : "Legend"}
      </button>
      {visible && (
        <ul>
          {KIND_ORDER.map((kind) => (
            <li key={kind}>
              <span className={`cg-swatch kind-${kind}`} />
              {KINDS[kind].label}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
