# Caldo Graph — implementation plan

Android Studio plugin that shows an interactive, UML-style graph of project classes and composables
in a JCEF tool window. Validated against a Kotlin Multiplatform app (Koin, MVI, Compose).

## Technical decisions

| Topic | Decision |
|---|---|
| Target IDE | Android Studio `2026.2.2.10` (latest stable patch), set in `gradle.properties` (`platformVersion`). `sinceBuild = 262`, no `untilBuild`. |
| JDK | **JDK 25 toolchain** (deviation from the brief's JDK 21): AS 2026.2 is compiled to Java 25 bytecode and requires Java 25 at runtime. |
| Build | Gradle wrapper 9.8, IntelliJ Platform Gradle Plugin 2.20.1, Kotlin 2.4.21, `bundledPlugin("org.jetbrains.kotlin")`, `bundledPlugin("com.intellij.java")` (PSI class APIs, short-name index). |
| JCEF | **AS 2026.2 does not bundle JCEF.** Since 2026.x it is the Marketplace plugin "Web Browser (JCEF)" (`com.intellij.modules.jcef`), built per OS/arch. The plugin declares it (plus the modules `intellij.platform.ui.jcef` and `intellij.libraries.jcef`) as a dependency, so the IDE offers to install it. Gradle adds the variant for the current OS/arch (`jcefPluginVersion` in `gradle.properties`) so that `runIde` and the tests have it. `verifyPlugin` reports it as an unresolved dependency because the verifier picks a Windows variant; that is a verifier limitation and not a compatibility problem. |
| Kotlin modes | `supportsKotlinPluginMode supportsK1="true" supportsK2="true"`; analysis code uses only the Analysis API (`analyze {}`), which works in both modes. |
| Serialization | `kotlinx.serialization` compiler plugin; the runtime is the copy bundled with the IDE (not packaged in the plugin). |
| Web UI | `webui/`: TypeScript + Vite + React + `@xyflow/react` and `html-to-image` (export); auto-layout is our own column layout (`columnLayout.ts`). Built by the Gradle task `buildWebUi` (`npm ci` + `npm run build`) and copied to `resources/webui`. Pass `-PskipWebUi` to reuse the existing `webui/dist`. |
| Serving the web app | Per-browser JCEF request handler that serves `https://classgraph.local/*` from plugin resources. No network access, no global scheme registration. |
| Bridge | Envelope `{v, type, payload}` (`v = 1`). JS → Kotlin via `JBCefJSQuery` (exposed as `window.__classGraphBridge.send`), Kotlin → JS via `executeJavaScript("window.__classGraphReceive(<json>)")`. Message types are mirrored in `bridge/Protocol.kt` and `webui/src/protocol.ts`. |
| Drag & drop | JCEF does not receive IDE-internal (PSI) drags. The browser runs in off-screen rendering (OSR) mode, so its component is a lightweight Swing component, and it is registered as a `DnDManager` target. That handles `TransferableWrapper` (Project / Structure views), `javaFileListFlavor` and `stringFlavor`. The drop point is forwarded to the web app. If OSR turns out not to work, fall back to a transparent Swing overlay. |
| Analysis threading | `ReadAction.nonBlocking { }.inSmartMode(project).expireWith(disposable).submit(executor)`; never on the EDT. |
| Theme | IDE colors sent as CSS variables (`theme` message) on load and on `LafManagerListener`. |
| Persistence | `PersistentStateComponent` stored in the workspace file. Kotlin decides which nodes exist; the web app owns the layout and sends the full snapshot (`graphChanged`) after each edit. |
| Node identity | `class:<fqName>` for classes; `fun:<fqName>(<param types>)@<file path>` for top-level functions. Resolved with the Kotlin stub indexes (`KotlinFullClassNameIndex`, `KotlinTopLevelFunctionFqnNameIndex`). |
| Analysis API on the EDT | Forbidden by the platform (`ProhibitedAnalysisException`). Production code always uses non-blocking read actions; fixture tests wrap the calls in `allowAnalysisOnEdt`. |

## Phases

### Phase 1 — Skeleton and technical risk
- [x] Gradle plugin project, plugin.xml, tool window anchored left with its own icon
- [x] Web app built by Gradle and served from resources (no network)
- [x] JS ↔ Kotlin bridge with ping/pong
- [x] Drag & drop prototype: dropping a file or declaration shows its FQN in the web app
- [x] `./gradlew test` (protocol + `DeclarationResolver` fixtures), `buildPlugin`, `verifyPlugin`; `npm run build` + `npm test`
- [x] `runIde` with the sample KMP project: both plugins load (Caldo Graph, Web Browser (JCEF)); the "Caldo Graph" stripe button is on the left
- [x] **Manual check with the sample KMP project** (plugin installed from the zip in Android Studio 2026.2): the tool window loads, and dropping `PlanCreationViewModel.kt`, `PlanMonthViewModel.kt` and `PlanDaysDeleteDelegate` from the Project view shows their FQNs

### Phase 2 — Nodes
- [x] Classification (`NodeClassifier`, Analysis API for the `androidx.lifecycle.ViewModel` supertype check) with colors per kind in light and dark themes; legend that can be shown or hidden
- [x] Public members (`MemberExtractor`): constructor `val`/`var` properties and body properties, methods with short signatures, composable parameters; inferred types are rendered through the Analysis API
- [x] Delete (✕, Delete/Backspace), move, multi-select (React Flow), collapse sections
- [x] Click on the header or a member navigates to the code (resolved again from the node id)
- [x] Persistence per project (`GraphStateService`, workspace file): the web app sends a layout snapshot after each edit; on load, nodes are re-analyzed and missing declarations are drawn as "not found"
- [x] A dropped file with several declarations adds the main one and offers the rest in a popup; ambiguous names (editor text) offer a chooser
- [x] "Add to Caldo Graph" action (Project view, Structure view, editor; `Ctrl+Alt+Shift+G`)
- [x] Tests: classification of every kind, members, composables, identity round-trip, drop resolution; web: protocol, package abbreviation, non-overlapping placement
- [ ] Criterion (manual): dragging `PlanCreationViewModel` shows a purple box with its public members, and the graph survives a restart

### Phase 3 — Relations
- [x] `RelationExtractor` (outgoing, Analysis API):
  - supertypes → inheritance / implementation
  - primary-constructor parameters → `«inject»`
  - body properties → composition when the initializer constructs the type, otherwise aggregation
  - public method signatures → dependency
  - composables: ViewModel parameter or `koinViewModel<X>()` → `«obtains»`, calls to project composables (also inside lambdas) → `«calls»`, `*State` / `*Ui` parameters → `«renders»`, `koinInject<X>()` → `«inject»`
- [x] Generic unwrapping (`List`/`Set`/`Map`/`Sequence`/`Array`/immutable collections → `*`; `Flow`/`StateFlow`/`LiveData`/`State`/`Lazy`/`Result`… are transparent; nullable → `0..1`); only classes in project source roots count (all KMP source sets)
- [x] `IncomingRelationFinder`: `ReferencesSearch` in project scope; each usage is mapped to its class or top-level function and kept only when that container's outgoing analysis reports it, so both directions agree. It runs as a background task, node by node, with progress and cancellation; cancelled nodes show "+ …", and clicking it searches again
- [x] "+" buttons with pending counters: right = dependencies (for interfaces also implementations), left = users
- [x] UML edges with custom SVG markers (hollow/filled diamond, hollow triangle, open arrow, dashed lines), labels and multiplicity
- [x] Expanded nodes are placed beside their parent without overlapping, and the view zooms to them; "remove with expanded nodes" (⊗)
- [x] Filters by relation kind and by layer (node kind), persisted; auto-layout (web button and IDE toolbar, see "Adjustments" below); zoom to fit
- [x] Tests: every relation kind, generic unwrapping, multiplicities, composables, incoming relations (interface → implementations and injections, composable callers); web: relation merging, pending counters, edge deduplication, expanded subtree
- [ ] Criterion (manual, sample KMP project): ViewModel → use cases (`«inject»`) → repository interface → implementation; `*Router` → `*Page` + ViewModel

Decisions in phase 3:
- All primary-constructor parameters count as `«inject»`, not only `val`s: in the sample project, ViewModels receive some use cases as plain parameters (`getChildren: GetChildrenUseCase`).
- `FooUseCaseImpl` is classified as UseCase (in the sample project, use cases are interfaces with `*Impl` classes).
- Incoming search uses `ReferencesSearch` only. Direct implementations are references in the supertype list, and indirect ones are not UML relations of the interface, so `DefinitionsScopedSearch` is not needed.
- Expanded nodes are placed with our own non-overlapping placement next to the parent (later replaced by the column layout, see "Adjustments").
- The relation and layer filters live in a web toolbar on the canvas; the IDE toolbar has Clear, Auto-Layout, Zoom to Fit and Reload.

### Phase 4 — Extras
- [x] Classification rules in Settings | Tools | Caldo Graph, stored per project in `.idea/classGraph.xml` (shareable): ordered table (first match wins) with kind, target (any/class/interface/function), name suffix, supertype (FQN or `*Suffix`, direct or indirect), annotation, modifier and file glob; "Reset to Defaults"; applying re-classifies the open graph. The hard-coded classifier was replaced by `NodeClassifier(rules)` with defaults that reproduce the earlier behaviour
- [x] Export to PNG (2×) / SVG from the IDE toolbar. The web app renders the visible graph with `html-to-image` and Kotlin saves it through the platform save dialog. Edge and marker styles are inline and the markers live inside the viewport so the export keeps them
- [x] "Add Class or Composable…" search popup (IDE toolbar) fed by the Kotlin short-name indexes in project scope; type to filter; ambiguous names offer a chooser
- [x] Live refresh: a PSI listener (800 ms debounce) re-analyzes nodes declared in the edited files and recomputes the outgoing relations of every declaration in them. That also updates the other nodes' incoming relations without a new usage search. Nodes whose declaration disappears are marked "not found" and come back if it reappears
- [x] Tests: custom rules (order, AND of conditions, supertype FQN/suffix through intermediate classes, globs), data-URL decoding, and a controller integration test (add → outgoing + incoming search → edit adds an implementation → incoming updated; deleting the declaration → "not found")

Decisions in phase 4:
- Rules are per project rather than per IDE: they describe a project's conventions and can be shared through VCS.
- Export uses `html-to-image` (the approach React Flow documents) because the canvas is HTML; SVG output embeds the HTML nodes through `foreignObject`.

Not verified manually: PNG export inside Android Studio. In the hidden browser pane used for checks, rasterization was unreliable (background tab); the SVG export was checked and contains nodes, edges, markers and theme variables. The Settings UI and live refresh still need a manual check in the IDE (live refresh is covered by the integration test).

## Validation
- `./gradlew test` (analysis fixtures), `./gradlew buildPlugin`, `./gradlew verifyPlugin`
- `cd webui && npm run build && npm test`
- Manual `runIde` check with a sample KMP project for each phase criterion (results recorded here)

## Adjustments after manual testing
- [x] Re-dropping a declaration that is already in the graph (file or class) offers the other declarations of its file; the popup only lists those not shown yet. ⇧/⌘-click picks several.
- [x] Delete/Backspace removes the selected boxes, or the box under the mouse when nothing is selected. The keys are registered on the tool window panel so the IDE does not keep them.
- [x] Mouse modes (bottom-left): ✋ free navigation (drag pans) and ⬚ selection (drag draws a selection box; right/middle button pans). The browser context menu is suppressed (Shift+right-click keeps it).
- [x] Right-click on the empty canvas opens a search box there (classes, interfaces, composables and `.kt` files; camel-hump matching through the platform's `NameUtil` matcher and the Kotlin/file name indexes). The chosen item is added at that point; a file adds its main declaration and offers the rest. The toolbar "+" uses the same popup.
- [x] "Add From the Same File" / "Choose Declaration" use a filterable checklist (`DeclarationChooserPopup`): typing filters by name (case-insensitive, camel humps), clicking a row toggles it, Add adds the checked items, Cancel adds nothing; Enter with nothing checked takes the highlighted row.
- [x] Auto-layout and "+" placement follow clean-architecture depth on the X axis: columns Router → Page → Composable → ViewModel → UseCase/Navigator → Repository interface → Repository impl → API/DataSource. An implementation goes half a column right of its interface. Helpers (models, states, actions, effects, `*Ui`, other) sit below the class they belong to, in its column. Rows align with connected nodes on the left. Edges pick facing handles: vertical between neighbouring boxes of a column, an arc on the left side when boxes lie in between. ELK was removed (it knows nothing about layers).
- [x] Helpers injected into a class (constructor parameter or `koinInject`) are dependencies: they go in the column right after the class that injects them, not below it. Only helpers the class holds or uses (state, actions, effects…) stay below.
