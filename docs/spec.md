# Spec: Swim — CMP rewrite of linear-viz

Decisions locked 2026-09-01: name **Swim** (CLI binary `swim`); nav events are **closures per exit**; v1 CLI ships **all 15 commands** — primary users are AGENTS, so the `--json` contract, exit codes, and stdout/stderr discipline are first-class API surface, not conveniences. Layout approach: OPEN — under discussion (user wants to fix real problems with the current ELK-based layout, not just re-implement it).

Community Kotlin Multiplatform project. Kotlin is the primary language for everything: apps, CLI, scripts.

Research input: `research/ecosystem-research.md` (OAuth, storage, distribution, klibs brief).

## 1. What the product is

A Linear issue dependency-graph visualizer + analysis toolkit:
- **App** (Android, iPad/iPhone, macOS desktop): interactive dependency graph — filter bar scoped by team/project/label/etc., paste-a-Linear-URL to derive filters, draggable nodes with persisted per-query layouts, relation create/change/delete, assignee changes, linked GitHub PR chips with review/check status, ready-set highlighting.
- **CLI** (`<name>` binary, Kotlin/Native): all 15 commands from the TS CLI (auth, list, show, teams, projects, labels, status, ready, next, blockers, downstream, relate, bulk-relate, comment-cleanup, refs) with identical flags, exit codes (0/1/2/3), stdout/stderr discipline, and `--json`/`--mermaid` outputs.
- **Agent files**: `agent.md` + `skill.md` shipped with the CLI so AI agents can drive it.

## 2. Modules

```
:core        KMP library — models, LinearClient (Ktor GraphQL), GithubClient,
             analysis, urlParser, mermaid, filter builder, OAuth (PKCE + device flow),
             TokenStore expect/actual, DeepLinkTarget resolver.
             Targets: jvm, android, iosArm64, iosSimulatorArm64, macosArm64, macosX64.
             Published to Maven Central → klibs.io.
:layout      KMP library — pure-Kotlin layered (Sugiyama) DAG layout. Separately
             publishable; the second klibs.io artifact. commonMain only.
:shared      CMP UI — graph canvas, chrome-free leaf screens taking contentPadding,
             theme (port the dark IDE palette), produces the iOS framework
             (baseName ComposeApp, isStatic, api+export :core).
:androidApp  Navigation 3 host (NavDisplay + entryProvider, explicit SerializersModule).
:iosApp      Xcode project. SwiftUI TabView/NavigationStack own navigation;
             per-screen Swift route enums (Hashable+Codable, ids-only), Compose leaves
             via one generic ComposeLeaf representable; NavigationManager with
             NavigationPath + codable restore per stack.
:desktopApp  Plain kotlinJvm + compose.desktop. Reuses the Nav3 host. jpackage Dmg,
             Developer ID signing + notarizeDmg. jvmToolchain(17).
:cli         Kotlin/Native (macosArm64, macosX64) binary on Clikt; shares :core.
```

Scaffold conventions: typesafe project accessors, content-filtered repositories, version catalog, root build.gradle.kts of `apply false` aliases, `.run/` configs, per-module `jvmToolchain(17)`. Versions: Kotlin 2.4.0 (stable, NOT the RC), CMP 1.12.0, Nav3 `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.1`, Ktor 3.5.2, AGP 9.0.1, Gradle 9.2.1. No web targets (also removes the source-set triplication hack).

## 3. Navigation (per the user's iOS navigation standard + the navigation recipe)

- CMP screens are **leaves inside native stacks**. No shared route type crossing the bridge; route keys per-platform.
- iOS: per-screen `[ScreenName]Route` enums in the screen's file; local builder + registration modifier; deep-link cases registered on root stacks only, same build function; all pushes via `NavigationManager.push`; no NavigationLink.
- Leaf factories in `iosMain`: one function per leaf wrapping a shared `leafController { AppProviders(graph) { … } }`; `OnFocusBehavior.DoNothing` + `usingNativeTextInput(true)`; `updateUIViewController` stays a no-op; payload captured at construction.
- **Ids cross the bridge as `String`** and are wrapped into value classes inside the leaf factory (value classes don't export to ObjC). Decision documented; applies to every leaf signature.
- Nav events: **closures per exit** on the leaf factory (compile-checked at the Swift call site), not a sealed event type. [DECIDED]
- Titles: never in routes. Pushing screen supplies the title; static titles for constant screens; `onTitleResolved` callback for deep-link entries.
- Android: Navigation 3 in `androidApp` — `@Serializable` NavKeys, `rememberNavBackStack` with explicit `SerializersModule` (no reflection off-JVM), saveable + viewmodel entry decorators.
- Desktop: reuses the Nav3 host inside `Window { }`; no desktop nav abstraction (nothing on the other side of it today).
- Shared nav code is exactly one thing: the KMP deep-link resolver emitting neutral `DeepLinkTarget`.
- This app's nav surface at v1 is small: Login, Graph (root), Issue detail (optional push), Settings. The pattern is the point for the community template value.

## 4. Auth (serverless — confirmed viable, ecosystem-research.md §1–2)

- **Linear**: OAuth 2.0 authorization-code + PKCE (S256), public client, no secret. Scopes `read,write`, `actor=user`. Endpoints per brief. Access token 24h + refresh tokens; handle `RATELIMITED` GraphQL errors (HTTP 400, not 429) + rate-limit headers.
  - Desktop + CLI redirect: loopback `http://127.0.0.1:<random port>/callback`.
  - Mobile redirect: Universal Link / App Link → static GitHub Pages page (`https://<user>.github.io/<name>/oauth/callback` + AASA + assetlinks.json). Empirically test custom-scheme first (5-min check) — docs say http/https only.
  - Registered OAuth app name must NOT contain "Linear"; manifest `distribution: public`.
- **GitHub**: device flow (no redirect needed on ANY platform), scope `repo` (no read-only private scope exists — document prominently; GitHub linking is optional, PR chips degrade gracefully). Handle `authorization_pending`/`slow_down`; org gotchas (OAuth-app restrictions, SAML SSO) get specific error messages.
- **Token storage** (`TokenStore` expect/actual): iOS + Native CLI → Keychain (`KeychainSettings`, multiplatform-settings 1.3.0); desktop JVM → `/usr/bin/security` shell-out; Android → Keystore AES-GCM key wrapping one string in DataStore. macOS app and CLI share the same keychain item (one login serves both). gh-style `--insecure-storage` file fallback documented. A `/usr/bin/security` read from a new binary causes a one time GUI authorization prompt, so task #0005 adds a native Keychain binding.
- First-launch flow in the app: Linear sign-in (required) → GitHub connect (optional, skippable) → graph. In-app sign-out calls `oauth/revoke` (App Store 5.1.1(v) requirement).

## 5. Core port 

- Data model ports 1:1; identifiers (not UUIDs) are graph keys; mutations resolve identifier→UUID.
- Linear GraphQL over plain **Ktor POST** + kotlinx.serialization (not Apollo; migration additive if ever needed). Reproduce the bulk-inline N+1 fix exactly: ISSUE_NODE_FIELDS + RELATION_FIELDS fragments, 250-page issue pagination, dual-direction relations deduped by relation id, page sizes as tuned (50 for projects-with-teams — complexity cap), 5-min TTL caches. **Note**: OAuth tokens use `Authorization: Bearer <token>`; personal API keys (if a fallback is kept) use no Bearer prefix.
- Analysis algorithms port faithfully including the conservative readiness rule (unknown blocker state = active).
- Deliberate improvements over the TS original (inventory "gaps to close"):
  1. Rate-limit/backoff handling (RATELIMITED + Retry-After).
  2. `findBlockerChain` dedupes emission.
  3. Project filters carry ids, not names.
  4. Mutations return typed results, not booleans.
  5. Cross-team key joined on a space → use a pair/data class.
- Removed the private-tool specifics: hardcoded repositories, an opinionated label filter, and POSIX-only paths.
- `repo.ts` (git-grep cross-referencing): CLI + desktop only (jvm/native actuals shelling to `git grep`); absent on mobile.

## 5b. Core session layer (added 2026-09-02, user-approved)

Principle: the CLI and the CMP app consume the SAME core for everything; each UI is a dumb projection of core output (CLI → text, CMP → interactive graph). All logic that lived only in the legacy Electron renderer moves into `swim.core.session`:

- **FilterStore** — the filter state machine from the legacy zustand store: edits ARM a reload (`shouldLoadIssues=false` + clear `urlSource`), `applyFilters` loads, `applyFromUrl` resets → applies resolved filters → records `urlSource` → loads immediately. Persistence via multiplatform-settings with the legacy include/exclude rules (`shouldLoadIssues`/`urlSource` never persist).
- **Option narrowing** — pure functions for available teams/projects/labels given the other selections (teams↔projects intersection, labels by team + workspace with lowercase-name dedupe) plus reconciliation that drops selections the other filters made impossible.
- **GraphSession** — StateFlow-based: graph load state, derived ready set, merged PR statuses, projected graph (hide-related / hide-duplicates toggles). Mutations (createRelation, changeRelation = delete+create, reversed-blocks creation, setAssignee) auto-invalidate. CLI uses the same suspend calls one-shot.
- **Classifiers** — ONE `stateCategory(stateName, stateType)` classifier replacing the legacy duplicated substring rules (IssueNode styling + mermaid STATE_STYLES); PR badge summarizer (review decision + check rollup → semantic level, PR number from URL, tooltip text). UIs map category → pixels/color.
- **authStatus()** over TokenStore (legacy `system:authStatus`).
- **Position cache** — the persisted-layout algorithm (closest-cached-layout reuse, fresh-nodes-shift-right, downward overlap resolution, hand-placed nodes never move) as pure functions in `:layout`; a `PositionStore` interface in `:core` (multiplatform-settings-backed) replaces localStorage.
- **API beef-up**: `ISSUE_NODE_FIELDS` gains `assignee { id name }` → `IssueNode.assigneeId`, killing the legacy match-current-user-by-display-name hack.

Stays UI: open-in-browser, confirm dialogs, paste gesture, colors/dash patterns, canvas gestures.

### PR-derived relations (added 2026-09-02, user-approved)

Linear relations are the source of truth for the layout. The pull requests add an overlay on top of them:

1. **Stacked pull requests.** If issue A has a pull request that starts from the branch of issue B's pull request, then **B blocks A**. The graph draws that edge even when Linear has no relation between the two issues.
2. **Shared branch.** If the pull requests of two issues use one head branch, the two issues make a **stack**. The surface draws a stack as a pile of cards with a diagonal offset. The blocker edges between the members of one stack are not drawn.
3. **Toggle.** "Derive relations from PR stacks", default ON. The toggle only draws or hides; it does not cause a load.

Rules:

- A Linear `blocks` relation between two issues always wins. The derived edge between the same two issues is dropped, in both directions.
- The state of the pull request does not matter. A merged or closed pull request still shows the order the work was done in.
- A derived edge carries `EdgeProvenance.PR_DERIVED` and has no relation id. Linear has nothing to delete, so the relation menu must not offer to change it or to delete it.
- **The placement waits for GitHub.** When the toggle is on and GitHub is configured, the load gets the pull-request answer BEFORE it reports the graph as loaded. The placement pass must see the derived edges the first time it runs, because an edge that arrives later moves cards the user is already reading. No GitHub token, or a GitHub failure, degrades to the plain Linear graph. The wait ends with the HTTP request: the GitHub client must have a request timeout, or a connection that gives no answer holds the graph back.
- The derived edges come after the Linear edges in the list given to `layout()`. A cycle-breaking pass thus sacrifices a derived edge before a Linear one.
- **The pull-request answer stays fresh.** Pull requests move while Linear stands still: one merges, one is retargeted, two issues fold onto one branch. While a graph is loaded, GitHub is connected and the toggle is on, the surface calls `refreshPrStatuses()` every `PR_STATUS_TTL`. The session then asks GitHub again, without asking Linear, and emits a new `Loaded` only if the answer differs. An unchanged minute costs one request and moves no card. `PR_STATUS_TTL` is one value: the window that suppresses a second ask, and the cadence of the refresh.
- A new GitHub token and a toggle turned back on both refresh at once. The refresh dies with the graph it belongs to: `transformLatest` cancels it on the next load, and the sharing coroutine ends it when the last collector goes.

Surface rules:

- A derived edge draws in the same solid red as a Linear `blocks` edge, at 0.55 alpha. No dash: a dashed `blocks` edge was rejected.
- Clicking or right-clicking a derived edge opens an info panel, not the change-and-remove chooser. The panel says which pull request targets which branch, and then says that the pull requests are stacked in that order for a reason and that the fix, if the order is wrong, is a new base on GitHub. **The app never offers to rebase.** The relation row for a derived edge in a card menu carries no options either.
- The toggle sits in view toolbar row 2. Without a GitHub token it is disabled and hints "Click to connect GitHub". The click then opens the GitHub card as a dialog over the graph, because a user who signed in to Linear before GitHub existed never sees the login screen's card. The overflow menu opens the same dialog. A connect that succeeds stores the token, refreshes the auth status, refreshes the pull requests and enables the toggle, all without a new session and without a new Linear load.
- **A stack is one layout slot.** `Placement` replaces the members of a stack with one synthetic layout node, `270 + 14·(n−1)` by `120 + 14·(n−1)`, and remaps every edge onto it, dropping the edges that then join the slot to itself. The slot is keyed `@stack:<lowest member identifier>`, which reserves `@stack:` in the position snapshot the way `@group:` is reserved for the area drag offsets. Unlike `@group:`, a pile is a real layout node, so its key stays in everything the layout cache reads and its position persists like any card's.
- The canvas fans the slot back out: card `i` of `n`, front first, sits `14·(n−1−i)` down and right of the slot. The front card is whole; the rest peek out at the top left. A `×n` badge sits off the front card, with the members on hover.
- The pile is one unit to select, to marquee, and to drag. Clicking a peeking rear card brings it to the front. That order is canvas state and is never persisted.

## 6. Graph UI + :layout

- Canvas: custom CMP canvas, node cards per the port notes (priority dot, mono identifier + copy button, state badge, estimate, 2-line title, state-colored footer, PR chips with review/check badges, assignee dropdown), edge styles (blocks solid red w/ arrowhead, related grey dashed, duplicate purple dashed), minimap, relation edit panel with confirm dialog.
- **The side panel.** There are no toolbar rows. A 280dp panel on the left holds every filter and every application control, and the canvas fills the rest of the window. The panel scrolls, and the `⌘\` key or the button in its header folds it to a 34dp rail. The canvas measures its own width, so the fit, the minimap and the hint bar all follow the panel.
  - The panel content changes with the selection. With nothing selected it shows the view controls. With one or more cards selected it puts a selection section on top: the identifiers, the title of a single issue, an assignee picker that changes the whole selection through ONE confirm, and the card-menu actions that apply — open in Linear, copy the identifier, remove from the project — plus Clear selection.
  - The panel itself carries no filter controls. It shows one summary chip per filter that is set, and the chip clears that filter. A team chip clears only its own team. The full set (team, project, label, exclude, priority, status type, state, assignee, include completed) lives in a **Filters modal** behind one button, with Clear, Cancel and Apply; Apply is the only control there that loads.
  - **Quick links.** A small `↗` beside the project chip opens the project's Linear page, and one beside each team chip opens that team's. Nothing else on a chip.
  - The rest of the panel: the Linear-URL field, Load/Reload, the group-by control, the view toggles (related edges, duplicates, PR-derived relations, cross-milestone links), the counts, and the application actions (Re-layout, Connect GitHub, Sign out).
- **Grouping defaults to Auto.** Auto is a session setting that resolves against the graph that loaded: any node with a milestone groups the graph by milestone, and a graph with none stays flat. An explicit choice of None, Team, Project, Label or Milestone overrides Auto and persists as before, and the Auto entry in the control gives the automatic behaviour back. The resolved grouping, not the stored choice, is what the layout cache keys on, so pinning Auto's own answer keeps the arrangement it already saved.
- **Area style.** A grouped area draws as a 1.5dp outline in a bright colour and nothing else: no fill and no wash over the cards. The colour rotates through a six-entry palette by area, and the area label is written in its own outline colour.
- **Panning and zooming.** Two-finger scroll pans both axes, in every mode. Cmd or ctrl with scroll zooms at the pointer. Nothing else pans: no drag of any button pans the view. The zoom step is exponential, so a large trackpad delta can never invert the direction or snap the scale to its limit.
- **Modes.** The canvas has two modes, `Arrange` (the default) and `Interact`. The `V` key and the `↖` button select Arrange. The `I` key and the `◉` button select Interact. Every mode change shows the new mode in a toast for one second, whatever caused the change.
  - **Arrange** moves the graph. A drag on a card moves that card, or the whole selection. A drag on empty canvas draws a selection box. A drag from a card handle draws a relation; the handles show on hover. After the user confirms a new "A blocks B", the app puts B and everything B blocks below A, moves them there over 200 ms, and saves the result: a relation the user drew is the user's own arrangement.
  - **Interact** acts on the graph and moves nothing. A click on a card opens the card menu. A click on an edge opens the edge panel. A drag on a card is refused: the card shakes, the mode toggle shakes, and the desktop beeps.
  - A plain click on a card or an edge in Arrange changes to Interact and then does what Interact does with that click. A click with shift or cmd held keeps its Arrange meaning and adds the card to the selection.
  - Right click opens the menu for the card, the edge, or the canvas, in both modes.
- **The card menu** (the same menu for a right click and for an Interact click): Open issue in Linear · Open GitHub PR · Pull request… · Copy ID · Assign to ▸ · Status ▸ · Priority ▸ · Points ▸ · Link a PR by URL… · Add relation ▸ · Relations ▸ · Remove from project. Every mutation goes through the confirm dialog. Linear holds no deep link to an attachment, so the only pull-request page the menu opens is GitHub's own.
- **The pull-request window** replaces the hover tooltip on a PR chip. A click on a chip, or the "Pull request…" row, opens an anchored panel with the number, the title, the head and base branches, the review and check state, and a link to GitHub.
- **Connector routing.** The surface layer calls `swim.layout.routeEdges` over the FINAL positions on screen, after the cache, any saved layout and every drop, and never reads `LayoutResult.routes`. An edge with a route draws as that polyline with the standard rounded corners, and takes its arrowhead from the last two points; its ends are the router's and are never re-derived. An edge with no route draws as before. Routes are found again whenever the positions change, off the main thread. The relation-drag ghost is always a direct line.
- Ready-set highlighting and card state styling per the substring-on-state-name rules (faithful port).
- **Card colours are the legacy renderer's, verbatim.** The legacy card did not outline itself from the IDE palette in the tailwind config: `getNodeStyling` used Tailwind's own default ramp. So the outline, the badge and the hover outline come from `-500` and `-400` (in progress `#EAB308`/`#FACC15`, in review `#22C55E`/`#4ADE80`, blocked `#EF4444`/`#F87171`, paused `#3B82F6`/`#60A5FA`, todo white at 80%), while the footer state text keeps the IDE hexes (`#3FB950`, `#F85149`, `#58A6FF`, `#9D9D9D`, `#6A6A6A`, and `#EAB308` for in progress). A canceled or invalid state groups with done everywhere but the footer text, where it greys out.
- **The two-tone card outline.** Legacy drew `border-2 border-{c}-500` with `ring-2 ring-{c}-500/30 ring-offset-2 ring-offset-transparent`. Compose draws the same sandwich inset instead of outset, so a card keeps its 270x120 box: the 30% halo runs along the card edge, a 2dp transparent gap shows the canvas through, and the solid line sits 4dp in. Done and backlog cards keep the legacy single hairline in `#3C3C3C` and reserve the same band, so every card has the same content width. The header carries the accent at 10% and the header and footer are ruled off at 30%, as they were.
- `:layout` [DECIDED — user-designed algorithm, not an ELK port]: **tidy blocker-trees.** Longest-path layering over `blocks` edges: roots (no blockers) at level 0, every task one level below its deepest blocker. Buchheim–Jünger–Leipert tidy-tree positioning: blocked tasks centered beneath their blocker with even x-spacing at a shared y; subtree contours reserve horizontal room so parent spacing grows with descendant width. Distinct trees packed side by side.
  - **Multi-blocker (DAG) rule:** primary parent = deepest blocker; the node joins that blocker's tree; remaining blocker edges render as cross-links (drawn, but ignored for placement).
  - **Cycles in `blocks`:** back-edge ignored for placement, drawn as an up-pointing cross-link in a WARNING style; cycles also surfaced in CLI `status` (a blocks-cycle is a planning bug — nothing in it can ever unblock).
  - **Duplicates:** issues on the duplicate side of a duplicate relation are HIDDEN by default; "Show duplicates" toggle (peer of "Include completed"). Verify direction semantics against Linear's schema.
  - **Related edges:** never structural. Soft affinity influencing sibling order under a parent and tree packing order (related nodes pull adjacent). Tunable weight; 0 disables.
  - **Form-factor split:** core is pure `layout(nodes, edges, params)` — spacing/density/direction params owned by the surface layer per form factor (phone/tablet/desktop).
  - **Workflow:** first `:layout` deliverable is a Compose desktop PLAYGROUND (hot reload, sample graphs) — the user writes/iterates the placement algorithm hands-on; Claude supplies the skeleton + a Buchheim tidy-tree starting implementation and the test harness (golden-graph tests).
  - Compound group-by (team/project/label) layers on top: each group lays out its own forest; groups packed as blocks.
- Layout cache: per-`(filters, groupBy)` positions persisted (multiplatform-settings), closest-cached-layout reuse + fresh-nodes-to-the-right shift + downward overlap resolution, ported per inventory pseudocode (with its documented O(n²) ceiling comment).
- Tablet first-class: material3-adaptive — filter rail/pane on wide layouts, bottom-sheet filters on phones; keyboard shortcuts (⌘±, fit, escape); hover tooltips on pointer devices.

## 7. Distribution

- **Own Homebrew tap** `github.com/<user>/homebrew-tap`: cask (dmg, `auto_updates` off, livecheck) + formula (CLI tar.gz; `pkgshare` gets agent.md/skill.md). README installs use FULLY QUALIFIED names (Homebrew 6 Tap Trust silently blocks short names).
- macOS: compose.desktop `packageReleaseDmg` + `notarizeDmg`, Developer ID Application cert. Notarization mandatory (brew Gatekeeper check).
- CI (GitHub Actions): PR build+test; tag release → dmg (signed+notarized), CLI binaries (arm64+x64 tar.gz), Maven Central publish of :core and :layout (klibs.io indexes automatically — good POM description + README), tap bump commit.
- Stores: Play + App Store. App Store: exempt from Sign in with Apple (4.8 third-party-client exception); demo Linear workspace credentials in review notes (2.1(a)); in-app revoke (5.1.1(v)). Play: demo credentials in App access.

## 8. Phases & orchestration

Gate = user review. Opus agents plan/review; Sonnet agents execute; deterministic scope extracted to scripts where possible.

- **P0 — Scaffold**: repo, Gradle skeleton per template recipe, CI skeleton, CLAUDE.md (one contract + build commands), docs/tasks tracker. Gate: user reviews skeleton.
- **P1 — :core + :layout** (parallel tracks): models/clients/analysis/auth with tests (analysis is pure → heavy unit tests; recorded GraphQL fixtures); layout with golden-graph tests. Gate: CLI-less smoke against real Linear workspace.
- **P2 — :cli**: Clikt commands over :core, native binaries, agent.md/skill.md. Gate: side-by-side output diff vs TS CLI on the same workspace.
- **P3 — :shared + :desktopApp**: graph canvas, filter bar, URL input, login flow; desktop packaging unsigned. Gate: user drives the app.
- **P4 — :androidApp + :iosApp**: native nav per recipe, tablet layouts, store-ready.
- **P5 — Distribution**: OAuth app registrations (user does these), signing/notarization, tap, Maven Central, Pages callback, store submissions.

## 9. Open decisions (user)

1. **Name** — must not contain "Linear" (OAuth registration rejects it; trademark hygiene anyway). Determines repo, packages, brew names, klibs listing.
2. **Nav events**: closures per exit (recommended, compile-checked in Swift) vs Kotlin sealed event type (matches doc wording, loses the check).
3. **Layout fidelity bar**: "visually comparable layered layout" (recommended) vs faithful ELK option-for-option port (much larger).
4. **v1 CLI surface**: all 15 commands (recommended — thin over core) vs graph-relevant subset first.
