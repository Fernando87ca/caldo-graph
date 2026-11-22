import { createContext, useContext } from "react";
import type { Side } from "./protocol";
import type { Pending } from "./relations";

export interface GraphActions {
  navigate(id: string, member: string | null): void;
  remove(id: string, withExpanded: boolean): void;
  toggleSection(id: string, section: string): void;
  expand(id: string, side: Side, targets: string[]): void;
  findIncoming(id: string): void;
}

/** Derived per-node information that changes when other nodes or relations change. */
export interface GraphInfo {
  pending: Map<string, Pending>;
  expandedChildren: Map<string, number>;
}

export const GraphActionsContext = createContext<GraphActions | null>(null);
export const GraphInfoContext = createContext<GraphInfo>({ pending: new Map(), expandedChildren: new Map() });

export function useGraphActions(): GraphActions {
  const actions = useContext(GraphActionsContext);
  if (!actions) throw new Error("GraphActionsContext is missing");
  return actions;
}

export const useGraphInfo = () => useContext(GraphInfoContext);
