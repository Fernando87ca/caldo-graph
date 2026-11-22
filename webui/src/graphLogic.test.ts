import { describe, expect, it } from "vitest";
import { abbreviatePackage, handlesFor, isClick, placeWithoutOverlap } from "./graphLogic";

describe("abbreviatePackage", () => {
  it("shortens all but the last segments", () => {
    expect(abbreviatePackage("com.example.app.features.mvi.plan.viewmodel")).toBe("c.e.a.f.m.plan.viewmodel");
    expect(abbreviatePackage("app.plan")).toBe("app.plan");
    expect(abbreviatePackage("")).toBe("");
  });
});

describe("placeWithoutOverlap", () => {
  const size = { width: 100, height: 50 };

  it("stacks new boxes below the anchor", () => {
    expect(placeWithoutOverlap({ x: 0, y: 0 }, [size, size], [])).toEqual([
      { x: 0, y: 0 },
      { x: 0, y: 74 },
    ]);
  });

  it("moves boxes below existing ones they would overlap", () => {
    const placed = placeWithoutOverlap({ x: 10, y: 10 }, [size], [{ x: 0, y: 0, ...size }]);
    expect(placed).toEqual([{ x: 10, y: 74 }]);
  });
});

describe("isClick", () => {
  it("tells a click from a drag", () => {
    expect(isClick({ x: 10, y: 10 }, { x: 13, y: 12 })).toBe(true);
    expect(isClick({ x: 10, y: 10 }, { x: 40, y: 10 })).toBe(false);
  });
});

describe("handlesFor", () => {
  const box = (x: number, y: number) => ({ x, y, width: 200, height: 100 });

  it("connects boxes of the same column vertically", () => {
    expect(handlesFor(box(0, 0), box(20, 300))).toEqual({ sourceHandle: "s-bottom", targetHandle: "t-top" });
    expect(handlesFor(box(0, 300), box(0, 0))).toEqual({ sourceHandle: "s-top", targetHandle: "t-bottom" });
  });

  it("arcs along the left side when other boxes of the column are in between", () => {
    expect(handlesFor(box(0, 0), box(0, 400), [box(0, 200)])).toEqual({ sourceHandle: "s-left", targetHandle: "t-left" });
    expect(handlesFor(box(0, 0), box(0, 400), [box(600, 200)])).toEqual({ sourceHandle: "s-bottom", targetHandle: "t-top" });
  });

  it("connects boxes of different columns through the facing sides", () => {
    expect(handlesFor(box(0, 0), box(400, 50))).toEqual({ sourceHandle: "s-right", targetHandle: "t-left" });
    expect(handlesFor(box(400, 0), box(0, 0))).toEqual({ sourceHandle: "s-left", targetHandle: "t-right" });
  });
});
