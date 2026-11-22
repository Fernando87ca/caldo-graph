import { memo } from "react";
import { Handle, Position, type Node, type NodeProps } from "@xyflow/react";
import { useGraphActions, useGraphInfo } from "./GraphActions";
import { abbreviatePackage } from "./graphLogic";
import { KINDS } from "./kinds";
import type { GraphNode, Member } from "./protocol";

/** One source and one target handle per side; edges pick the pair that faces the other node (see graphLogic.handlesFor). */
const HANDLES: [string, "source" | "target", Position][] = [
  ["s-right", "source", Position.Right],
  ["s-left", "source", Position.Left],
  ["s-top", "source", Position.Top],
  ["s-bottom", "source", Position.Bottom],
  ["t-right", "target", Position.Right],
  ["t-left", "target", Position.Left],
  ["t-top", "target", Position.Top],
  ["t-bottom", "target", Position.Bottom],
];

export type ClassNodeData = { node: GraphNode; collapsed: string[]; parent: string | null };
export type ClassFlowNode = Node<ClassNodeData, "class">;

interface Section {
  key: string;
  title: string;
  members: Member[];
}

function sectionsOf(node: GraphNode): Section[] {
  return [
    { key: "parameters", title: "Parameters", members: node.parameters },
    { key: "properties", title: "Properties", members: node.properties },
    { key: "functions", title: "Methods", members: node.functions },
  ].filter((s) => s.members.length > 0);
}

function ClassNodeComponent({ data, selected }: NodeProps<ClassFlowNode>) {
  const { node, collapsed } = data;
  const actions = useGraphActions();
  const info = useGraphInfo();
  const pending = info.pending.get(node.id);
  const expandedChildren = info.expandedChildren.get(node.id) ?? 0;
  const kind = KINDS[node.kind];
  const stereotype = node.isInterface && node.kind !== "repositoryInterface" ? "interface" : kind.stereotype;
  const classes = [
    "cg-class",
    `kind-${node.kind}`,
    node.isInterface ? "is-interface" : "",
    node.notFound ? "is-not-found" : "",
    selected ? "is-selected" : "",
  ].join(" ");

  return (
    <div className="cg-node-shell">
      {HANDLES.map(([id, type, position]) => (
        <Handle key={id} id={id} type={type} position={position} className="cg-handle" />
      ))}
      {pending && !node.notFound && (
        <>
          <ExpandButton
            side="left"
            count={pending.left?.length ?? null}
            title={pending.left === null ? "Searching usages… (click to search again)" : "Add incoming: who uses, implements or calls it"}
            onClick={() => (pending.left === null ? actions.findIncoming(node.id) : actions.expand(node.id, "left", pending.left))}
          />
          <ExpandButton
            side="right"
            count={pending.right.length}
            title={node.isInterface ? "Add dependencies and implementations" : "Add outgoing: dependencies"}
            onClick={() => actions.expand(node.id, "right", pending.right)}
          />
        </>
      )}
      <div className={classes} title={node.notFound ? "Declaration not found" : `${node.packageName}.${node.name}`}>
        <header className="cg-class-header" onClick={() => actions.navigate(node.id, null)}>
          <span className="cg-kind-icon">{kind.icon}</span>
          <div className="cg-class-title">
            {stereotype && <div className="cg-stereotype">«{stereotype}»</div>}
            <div className="cg-name">{node.name}</div>
            <div className="cg-package">{abbreviatePackage(node.packageName)}</div>
            {node.notFound && <div className="cg-not-found">not found</div>}
          </div>
          {expandedChildren > 0 && (
            <button
              className="cg-icon-button nodrag"
              title={`Remove with its expanded nodes (${expandedChildren})`}
              onClick={(e) => {
                e.stopPropagation();
                actions.remove(node.id, true);
              }}
            >
              ⊗
            </button>
          )}
          <button
            className="cg-icon-button nodrag"
            title="Remove from graph"
            onClick={(e) => {
              e.stopPropagation();
              actions.remove(node.id, false);
            }}
          >
            ✕
          </button>
        </header>
        {sectionsOf(node).map((section) => {
          const isCollapsed = collapsed.includes(section.key);
          return (
            <section key={section.key} className="cg-section">
              <button className="cg-section-title nodrag" onClick={() => actions.toggleSection(node.id, section.key)}>
                <span className="cg-chevron">{isCollapsed ? "▸" : "▾"}</span>
                {section.title}
                <span className="cg-count">{section.members.length}</span>
              </button>
              {!isCollapsed && (
                <ul className="cg-members">
                  {section.members.map((m) => (
                    <li key={`${m.kind}:${m.name}${m.signature}`} className="nodrag" onClick={() => actions.navigate(node.id, m.name)}>
                      <span className="cg-member-name">{m.name}</span>
                      <span className="cg-member-signature">{m.signature}</span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          );
        })}
      </div>
    </div>
  );
}

function ExpandButton({ side, count, title, onClick }: { side: "left" | "right"; count: number | null; title: string; onClick(): void }) {
  if (count === 0) return null;
  return (
    <button
      className={`cg-expand cg-expand-${side} nodrag`}
      title={title}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
    >
      + {count ?? "…"}
    </button>
  );
}

export const ClassNode = memo(ClassNodeComponent);
