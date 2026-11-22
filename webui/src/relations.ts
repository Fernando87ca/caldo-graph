import type { NodeRelations, Relation, RelationKind } from "./protocol";

export type RelationIndex = Map<string, { outgoing: Relation[] | null; incoming: Relation[] | null }>;

export function mergeRelations(index: RelationIndex, items: NodeRelations[]): RelationIndex {
  const next = new Map(index);
  for (const item of items) {
    const current = next.get(item.id) ?? { outgoing: null, incoming: null };
    next.set(item.id, {
      outgoing: item.outgoing ?? current.outgoing,
      incoming: item.incoming ?? current.incoming,
    });
  }
  return next;
}

export const relationKey = (r: Relation) => `${r.source}|${r.target}|${r.kind}`;

/** All known relations whose two ends are in [present], without duplicates (each is known from both ends). */
export function visibleRelations(index: RelationIndex, present: Set<string>, hiddenKinds: Set<RelationKind>): Relation[] {
  const byKey = new Map<string, Relation>();
  for (const { outgoing, incoming } of index.values()) {
    for (const r of [...(outgoing ?? []), ...(incoming ?? [])]) {
      if (present.has(r.source) && present.has(r.target) && !hiddenKinds.has(r.kind)) byKey.set(relationKey(r), r);
    }
  }
  return [...byKey.values()];
}

export interface Pending {
  /** Dependencies not in the graph yet; for interfaces also their implementations. */
  right: string[];
  /** Users not in the graph yet; null while the usage search has not finished. */
  left: string[] | null;
}

export function pendingFor(id: string, index: RelationIndex, present: Set<string>, isInterface: boolean): Pending {
  const relations = index.get(id);
  const unique = (ids: string[]) => [...new Set(ids)].filter((other) => other !== id && !present.has(other));
  const implementations = (relations?.incoming ?? []).filter((r) => r.kind === "implementation").map((r) => r.source);
  return {
    right: unique([...(relations?.outgoing ?? []).map((r) => r.target), ...(isInterface ? implementations : [])]),
    left: relations?.incoming == null ? null : unique(relations.incoming.map((r) => r.source)),
  };
}

/** [id] plus every node expanded from it, transitively. */
export function withExpanded(id: string, parents: Map<string, string | null>): Set<string> {
  const result = new Set([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const [child, parent] of parents) {
      if (parent && result.has(parent) && !result.has(child)) {
        result.add(child);
        grew = true;
      }
    }
  }
  return result;
}
