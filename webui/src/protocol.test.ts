import { describe, expect, it } from "vitest";
import { envelope, parseInbound, PROTOCOL_VERSION } from "./protocol";

describe("protocol", () => {
  it("wraps outbound messages in a versioned envelope", () => {
    expect(envelope("navigate", { id: "class:a.B", member: null })).toEqual({
      v: PROTOCOL_VERSION,
      type: "navigate",
      payload: { id: "class:a.B", member: null },
    });
  });

  it("parses inbound messages from objects and JSON strings", () => {
    const msg = { v: PROTOCOL_VERSION, type: "focusNode", payload: { id: "class:a.B" } };
    expect(parseInbound(msg)).toEqual(msg);
    expect(parseInbound(JSON.stringify(msg))).toEqual(msg);
  });

  it("rejects malformed or mismatched versions", () => {
    expect(parseInbound("{not json")).toBeNull();
    expect(parseInbound({ v: PROTOCOL_VERSION + 1, type: "pong", payload: {} })).toBeNull();
    expect(parseInbound({ v: PROTOCOL_VERSION, payload: {} })).toBeNull();
  });
});
