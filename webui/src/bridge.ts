import { InboundMessage, OutboundMessage, parseInbound } from "./protocol";

declare global {
  interface Window {
    /** Installed by the plugin once the page has loaded. */
    __classGraphBridge?: { send(json: string): void };
    /** Called by the plugin through executeJavaScript. */
    __classGraphReceive?: (message: unknown) => void;
  }
}

type Listener = (message: InboundMessage) => void;

const listeners = new Set<Listener>();
const pending: string[] = [];

window.__classGraphReceive = (raw) => {
  const message = parseInbound(raw);
  if (!message) {
    console.warn("Caldo Graph: ignoring malformed message", raw);
    return;
  }
  listeners.forEach((l) => l(message));
};

// The plugin injects the bridge after page load and then fires this event.
window.addEventListener("classgraph-bridge-ready", () => {
  pending.splice(0).forEach((json) => window.__classGraphBridge!.send(json));
});

export function send(message: OutboundMessage): void {
  const json = JSON.stringify(message);
  if (window.__classGraphBridge) window.__classGraphBridge.send(json);
  else pending.push(json);
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function isEmbedded(): boolean {
  return window.__classGraphBridge !== undefined;
}
