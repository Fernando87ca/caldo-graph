import { describe, expect, it } from "vitest";
import type { Relation } from "./protocol";
import { mergeRelations, pendingFor, visibleRelations, withExpanded, type RelationIndex } from "./relations";

const rel = (source: string, target: string, kind: Relation["kind"] = "injection"): Relation => ({
  source,
  target,
  kind,
  label: null,
  multiplicity: null,
});

describe("relations", () => {
  const index: RelationIndex = mergeRelations(new Map(), [
    { id: "vm", outgoing: [rel("vm", "useCase"), rel("vm", "state", "composition")], incoming: null },
    { id: "repo", outgoing: [], incoming: [rel("impl", "repo", "implementation"), rel("useCase", "repo")] },
    { id: "useCase", outgoing: [rel("useCase", "repo")], incoming: [rel("vm", "useCase")] },
  ]);

  it("keeps known lists when an update only brings the other direction", () => {
    const merged = mergeRelations(index, [{ id: "vm", outgoing: null, incoming: [] }]);
    expect(merged.get("vm")).toEqual({ outgoing: index.get("vm")!.outgoing, incoming: [] });
  });

  it("counts pending dependencies and users, and unknown users as null", () => {
    expect(pendingFor("vm", index, new Set(["vm"]), false)).toEqual({ right: ["useCase", "state"], left: null });
    expect(pendingFor("vm", index, new Set(["vm", "useCase"]), false).right).toEqual(["state"]);
  });

  it("offers interface implementations on the right side", () => {
    expect(pendingFor("repo", index, new Set(["repo"]), true)).toEqual({ right: ["impl"], left: ["impl", "useCase"] });
  });

  it("draws each relation once, only between present nodes and for visible kinds", () => {
    const present = new Set(["vm", "useCase", "repo"]);
    expect(visibleRelations(index, present, new Set()).map((r) => `${r.source}>${r.target}`)).toEqual(["vm>useCase", "useCase>repo"]);
    expect(visibleRelations(index, present, new Set(["injection"]))).toEqual([]);
  });

  it("collects nodes expanded from a node transitively", () => {
    const parents = new Map<string, string | null>([
      ["vm", null],
      ["useCase", "vm"],
      ["repo", "useCase"],
      ["other", null],
    ]);
    expect([...withExpanded("vm", parents)]).toEqual(["vm", "useCase", "repo"]);
  });
});
