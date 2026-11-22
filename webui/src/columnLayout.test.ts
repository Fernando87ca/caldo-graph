import { describe, expect, it } from "vitest";
import { columnLayout, sideFor, type LayoutInput } from "./columnLayout";
import type { GraphNode, NodeKind, Relation } from "./protocol";

const graphNode = (id: string, kind: NodeKind, isInterface = false): GraphNode => ({
  id,
  kind,
  name: id,
  packageName: "app",
  isInterface,
  properties: [],
  functions: [],
  parameters: [],
  notFound: false,
});

const input = (id: string, kind: NodeKind, parent: string | null = null, isInterface = false): LayoutInput => ({
  id,
  node: graphNode(id, kind, isInterface),
  width: 200,
  height: 100,
  parent,
  position: { x: 0, y: 0 },
});

const rel = (source: string, target: string, kind: Relation["kind"]): Relation => ({ source, target, kind, label: null, multiplicity: null });

describe("columnLayout", () => {
  const nodes = [
    input("vm", "viewModel"),
    input("state", "model", "vm"),
    input("action", "model", "vm"),
    input("draft", "model", "state"),
    input("createPlan", "useCase", "vm", true),
    input("createPlanImpl", "useCase", "createPlan"),
    input("repo", "repositoryInterface", "createPlanImpl", true),
    input("repoImpl", "repositoryImpl", "repo"),
    input("page", "composablePage"),
    input("range", "model", "vm"),
    input("exit", "other", "vm", true),
  ];
  const relations = [
    rel("vm", "state", "composition"),
    rel("vm", "action", "dependency"),
    rel("state", "draft", "injection"),
    rel("vm", "createPlan", "injection"),
    rel("createPlanImpl", "createPlan", "implementation"),
    rel("createPlanImpl", "repo", "injection"),
    rel("repoImpl", "repo", "implementation"),
    rel("page", "state", "renders"),
    rel("vm", "range", "injection"),
    rel("vm", "exit", "injection"),
  ];
  const layout = columnLayout(nodes, relations);
  const x = (id: string) => layout.get(id)!.x;
  const y = (id: string) => layout.get(id)!.y;

  it("orders columns by clean-architecture depth", () => {
    expect(x("page")).toBeLessThan(x("vm"));
    expect(x("vm")).toBeLessThan(x("createPlan"));
    expect(x("createPlan")).toBeLessThan(x("createPlanImpl"));
    expect(x("createPlanImpl")).toBeLessThan(x("repo"));
    expect(x("repo")).toBeLessThan(x("repoImpl"));
  });

  it("puts helpers below the class they belong to, in its column", () => {
    for (const helper of ["state", "action", "draft"]) {
      expect(x(helper)).toBe(x("vm"));
      expect(y(helper)).toBeGreaterThan(y("vm"));
    }
  });

  it("puts helpers injected into a class in the next column, like other dependencies", () => {
    for (const injected of ["range", "exit"]) {
      expect(x(injected)).toBeGreaterThan(x("vm"));
      expect(x(injected)).toBeLessThan(x("repo"));
    }
  });

  it("aligns a node with the neighbours on its left", () => {
    expect(y("createPlan")).toBe(y("vm"));
    expect(y("repo")).toBe(y("createPlanImpl"));
  });

  it("never overlaps nodes of the same column", () => {
    const column = ["vm", "state", "action", "draft"].map(y).sort((a, b) => a - b);
    column.slice(1).forEach((value, i) => expect(value - column[i]).toBeGreaterThanOrEqual(100));
  });
});

describe("sideFor", () => {
  it("places deeper layers right, shallower left and helpers below", () => {
    const vm = graphNode("vm", "viewModel");
    expect(sideFor(vm, graphNode("u", "useCase"), "left")).toBe("right");
    expect(sideFor(vm, graphNode("p", "composablePage"), "right")).toBe("left");
    expect(sideFor(vm, graphNode("s", "model"), "right")).toBe("below");
    expect(sideFor(graphNode("i", "useCase", true), graphNode("impl", "useCase"), "right")).toBe("right");
    expect(sideFor(vm, graphNode("range", "model"), "right", true)).toBe("right");
  });
});
