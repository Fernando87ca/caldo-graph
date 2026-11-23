# Caldo Graph

An Android Studio plugin that turns your Kotlin code into an interactive, UML-style class graph.

Drop classes and composables onto a canvas and grow the graph from there. Each "+" button expands what a class
depends on or what depends on it, so you can follow a feature from its UI down to its data sources without leaving
the IDE. It is built for modern Android and Kotlin Multiplatform codebases: Compose, ViewModels, use cases,
repositories and dependency injection.

## Features

- **Several diagrams, as editor tabs.** The Caldo Graph tool window lists the project's diagrams: create, open,
  rename (F2) and delete them. Each diagram opens as an editor tab, so you can close it, drag it to a side of the
  editor, or use *Split Right* / *Split Down* (also nested), just like code tabs. A diagram is shown in one tab
  at a time, so splitting a diagram tab moves it to the new split. "Add to Caldo Graph" adds to the diagram you
  selected last.
- **Interactive canvas** rendered with an embedded browser (JCEF) and React Flow. Pan, zoom, multi-select,
  collapse member sections and delete boxes.
- **Many ways to add declarations**:
  - drag & drop from the Project view, the Structure view or the editor
  - the **Add to Caldo Graph** action (`Ctrl+Alt+Shift+G`)
  - right-click on the canvas to search classes, composables and files by name
  - When a file holds several declarations, you can pick the ones you want.
- **UML boxes coloured by role**: Page, Router and other composables, ViewModel, Navigator, UseCase, Repository
  (interface and implementation), API/DataSource and models. Each box shows its stereotype, public properties,
  public methods and composable parameters. Click a box or a member to jump to the code.
- **Relations with UML notation**, labelled with the property or parameter name:
  - inheritance and implementation
  - `«inject»` (constructor injection, `koinInject`)
  - composition and aggregation, with multiplicity (`1`, `0..1`, `*`)
  - dependency
  - for composables, `«obtains»` (ViewModels), `«calls»` (other composables) and `«renders»` (state and UI models)
  - Common wrappers are unwrapped: `List`, `Set`, `Flow`, `StateFlow`, `LiveData`, nullables…
  - Only declarations from your own sources count, across all KMP source sets.
- **Outgoing and incoming expansion** with pending counters. Incoming relations are found with the platform's
  reference search, in a cancellable background task. Expanding an interface shows its implementations.
- **Clean-architecture layout**: the X axis is the architectural depth (UI → ViewModel → use cases →
  repositories → data sources). Helper models sit below the class they belong to.
- **Live refresh**: boxes and relations follow your code edits. Declarations that disappear are marked as
  *not found*.
- **Filters** by relation kind and by layer, a toggleable legend, and **PNG/SVG export**.
- **Configurable classification rules** (Settings → Tools → Caldo Graph): map supertypes, name suffixes,
  annotations, modifiers or file patterns to node kinds. Rules are stored per project in `.idea/classGraph.xml`.
- **Per-project persistence**: the diagrams (nodes, positions, collapsed sections and filters) and the open tabs and
  splits are restored when you reopen the project.
- Follows the IDE's light/dark theme and supports both the K1 and K2 Kotlin plugin modes.

## Requirements

- Android Studio 2026.2 (build 262) or newer.
- The **Web Browser (JCEF)** plugin from JetBrains Marketplace. Recent Android Studio versions no longer bundle
  JCEF; the IDE offers to install it when you install Caldo Graph.

## Installation

1. Build the plugin (see below) or download a release zip.
2. In Android Studio: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick
   `build/distributions/caldo-graph-<version>.zip`.
3. Restart the IDE and open the **Caldo Graph** tool window.

## Building from source

Prerequisites: JDK 25 (or let Gradle provision it through the toolchain resolver) and Node.js with npm.

```bash
./gradlew buildPlugin      # builds the web UI and the plugin zip into build/distributions
./gradlew test             # analysis, bridge and controller tests
./gradlew verifyPlugin     # IntelliJ Plugin Verifier against the target Android Studio
./gradlew runIde           # starts a sandboxed Android Studio with the plugin
```

Open a project directly in the sandbox with `./gradlew runIde -PrunIdeProject=/path/to/project`.
The target Android Studio version is set by `platformVersion` in `gradle.properties`.

### Web UI

The canvas lives in `webui/` (TypeScript, Vite, React, React Flow). Gradle builds it automatically, but you can
also work on it on its own:

```bash
cd webui
npm install
npm run dev     # standalone dev server, without the IDE bridge
npm test        # graph, layout and protocol tests
```

Pass `-PskipWebUi` to Gradle to reuse an existing `webui/dist` build.

## How it works

- `analysis/`: classifies declarations, extracts their members and relations with PSI and the Kotlin Analysis
  API, and searches incoming references. It always runs in non-blocking read actions and respects dumb mode.
- `bridge/`: a versioned JSON protocol between Kotlin and the web UI (`JBCefJSQuery` one way,
  `executeJavaScript` the other).
- `toolwindow/`: the JCEF panel, the drag & drop target, popups, export and the graph controller.
- `state/` and `settings/`: per-project graph state and classification rules.
- `webui/`: the React Flow canvas, UML edges and markers, filters and the column layout.

The web UI is bundled in the plugin and served locally from its resources; the plugin makes no network requests.
See [`docs/plan.md`](docs/plan.md) for the technical decisions and the development log.
