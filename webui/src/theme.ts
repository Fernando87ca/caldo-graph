/** Applies IDE colors as CSS variables, e.g. `{ background: "#1e1f22" }` → `--cg-background`. */
export function applyTheme(colors: Record<string, string>, root: HTMLElement = document.documentElement): void {
  for (const [name, value] of Object.entries(colors)) {
    root.style.setProperty(`--cg-${name}`, value);
  }
}
