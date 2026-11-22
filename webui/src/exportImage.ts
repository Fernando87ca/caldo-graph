import { getNodesBounds, type Node } from "@xyflow/react";
import { toPng, toSvg } from "html-to-image";

const PADDING = 40;

/** Renders all visible nodes and edges (not the viewport) into a PNG or SVG data URL. */
export async function exportImage(format: "png" | "svg", nodes: Node[]): Promise<string> {
  const viewport = document.querySelector<HTMLElement>(".react-flow__viewport");
  if (!viewport || nodes.length === 0) throw new Error("Nothing to export");
  const bounds = getNodesBounds(nodes);
  const width = Math.ceil(bounds.width + PADDING * 2);
  const height = Math.ceil(bounds.height + PADDING * 2);
  const options = {
    backgroundColor: getComputedStyle(document.body).backgroundColor,
    width,
    height,
    pixelRatio: format === "png" ? 2 : 1,
    style: {
      width: `${width}px`,
      height: `${height}px`,
      transform: `translate(${PADDING - bounds.x}px, ${PADDING - bounds.y}px) scale(1)`,
    },
    // Buttons that only make sense on screen.
    filter: (element: HTMLElement) => !element.classList?.contains("cg-expand") && !element.classList?.contains("cg-icon-button"),
  };
  const render = () => (format === "png" ? toPng(viewport, options) : toSvg(viewport, options));
  // html-to-image can return a blank first frame while fonts and resources are being embedded.
  await render();
  return render();
}
