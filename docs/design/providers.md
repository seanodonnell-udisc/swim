# Design: providers, swim relations, and swim groups

Status: proposal. Written 2026-09-03. The owner must answer the open questions at the end before
phase P6 starts.

This document describes how Swim moves from a Linear-shaped core to a pluggable one. It names the
real types and the real files that change. It does not design the plugin system.

## 0. What is true today

Read these files first. They are the ones this design changes.

| File | What it holds now |
|---|---|
| `core/src/commonMain/kotlin/swim/core/model/IssueNode.kt` | `IssueNode`, keyed by `identifier`. `milestone` and `milestoneId` are Linear fields. |
| `core/src/commonMain/kotlin/swim/core/model/IssueEdge.kt` | `RelationType`, `EdgeProvenance { LINEAR, PR_DERIVED }`, `IssueEdge`. |
| `core/src/commonMain/kotlin/swim/core/model/GraphData.kt` | `nodes`, `edges`, `externalBlockerStates`, `stacks`. |
| `core/src/commonMain/kotlin/swim/core/linear/LinearClient.kt` | A concrete class. Reference data, `getIssuesWithRelations`, and every mutation. |
| `core/src/commonMain/kotlin/swim/core/github/GithubClient.kt` | `getPrStatuses`, `verifyToken`. |
| `core/src/commonMain/kotlin/swim/core/session/GraphSession.kt` | The load, the pull-request follow-up, the mutations, and `layoutCacheKey`. |
| `core/src/commonMain/kotlin/swim/core/session/PrRelations.kt` | `withPrRelations`, `derivedBlocks`, `prStacks`. |
| `core/src/commonMain/kotlin/swim/core/session/FilterStore.kt` | `GraphGrouping`, `resolveGrouping`, the persisted filter state. |
| `core/src/commonMain/kotlin/swim/core/session/PositionStore.kt` | `PositionStore`, `cacheKey`, `groupingOf`, `EDITED_KEY`. |
| `core/src/jvmMain/kotlin/swim/core/session/FilePositionStore.kt` | The file-backed store. Settings cap one value at 8 KB. |
| `shared/src/commonMain/kotlin/swim/ui/app/Placement.kt` | `groupKeyOf`, `GROUP_OFFSET_PREFIX`, `placeGraph`. |
| `shared/src/commonMain/kotlin/swim/ui/graph/ContextMenu.kt` | `isDerived`, `derivedEdgeLines`, `menuEntries`. |
| `shared/src/commonMain/kotlin/swim/ui/app/SwimApp.kt` | The app builds `LinearClient`, `GithubClient` and `GraphSession`. |
| `cli/src/commonMain/kotlin/swim/cli/Runtime.kt` | The CLI builds the same two clients. |

Two facts control the whole migration:

1. `CLAUDE.md` states the contract: graph keys are Linear identifiers such as `ENG-123`.
   `IssueEdge.from`, `IssueEdge.to`, `GraphData.stacks`, every position key, and every analysis
   function use that key.
2. Positions are the only graph data on disk. `GraphData` and `IssueEdge` are `@Serializable`, but
   no store writes them. A change to `EdgeProvenance` therefore costs nothing on disk. A change to
   a position key does cost, and `cacheKey` writes `GraphGrouping` by name.

## 1. Node identity

### 1.1 The URI

Every node gets one stable text key.

```
<provider>:<kind>/<local>
```

- `provider` is the provider id. It is lower case and matches `[a-z0-9-]+`.
- `kind` is a node kind slug, also lower case. It is a string, not an enum.
- `local` is the provider's own stable key. It is everything after the first `/`. It may hold more
  slashes.

Examples:

```
linear:issue/ENG-123
jira:issue/PROJ-7
github:pr/acme/web/482
link:url/https%3A%2F%2Fexample.com%2Fplan
note:note/6f1c0b2a
agent:session/run-2026-09-03-14
```

```kotlin
@Serializable
@JvmInline
value class NodeUri(val text: String) {
    val provider: String get() = text.substringBefore(':')
    val kind: String get() = text.substringAfter(':').substringBefore('/')
    val local: String get() = text.substringAfter('/')

    companion object {
        /** A key with no `:` is a legacy Linear identifier. */
        fun parse(key: String): NodeUri =
            if (':' in key) NodeUri(key) else NodeUri("linear:issue/$key")

        fun of(provider: String, kind: String, local: String) = NodeUri("$provider:$kind/$local")
    }
}
```

The value class holds a plain string. It needs no parser and no library. Percent-encode the local
part only when it holds a character that breaks a store, such as a URL.

### 1.2 Why the reserved keys still work

`Placement.kt` reserves `@group:` and `@stack:`. `PositionStore.kt` reserves `@edited`. A provider
id matches `[a-z0-9-]+`, so a `NodeUri` can never start with `@`. No prefix collides.

### 1.3 Display is not identity

`ENG-123` stays on the card. Add a display field and keep the URI as the key.

```kotlin
data class IssueNode(
    val id: String,
    val identifier: String,
    val uri: NodeUri = NodeUri.of("linear", "issue", identifier),
    ...
)
```

A Kotlin constructor default can read an earlier parameter, so `uri` needs no call-site change on
day one. Rename `IssueNode` to `WorkNode` later, or never. See open question 11.

### 1.4 Migration of saved layouts

Saved position keys hold bare identifiers today. Two rules make every saved layout survive:

1. `decodeSnapshot` in `PositionStore.kt` maps each non-reserved key through `NodeUri.parse`. A
   bare `ENG-123` reads as `linear:issue/ENG-123`.
2. `encodeSnapshot` writes the URI form. The file upgrades itself the first time the user drags a
   card.

No migration script runs. No user loses an arrangement. `cacheKey` does not change in this step,
because it encodes filters and grouping, not node keys.

## 2. Provider interfaces

### 2.1 Two kinds of provider

A work tracker and a version control host answer different questions. Keep two interfaces.

```kotlin
enum class ProviderCapability {
    READ_NODES, READ_RELATIONS, READ_GROUPS,
    WRITE_RELATIONS, WRITE_ASSIGNEE, WRITE_STATE, WRITE_PRIORITY,
    WRITE_ESTIMATE, WRITE_GROUP_MEMBERSHIP, ATTACH_LINK,
}

/** A work tracker. Linear, Jira, Asana. */
interface WorkProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ProviderCapability>

    suspend fun nodes(scope: ProviderScope): List<IssueNode>
    suspend fun relations(nodes: List<IssueNode>): List<IssueEdge>
    suspend fun groups(scope: ProviderScope): List<ProviderGroup>
    suspend fun mutate(request: NodeMutation): MutationResult
}

/** A version control host. GitHub, GitLab, Bitbucket. */
interface TopologyProvider {
    val id: String
    val displayName: String

    /** Head branch, base branch, review state and check state for every link. */
    suspend fun topology(links: List<String>): BranchTopology?
}
```

`ProviderGroup` is the tracker's own grouping feature. A Linear milestone, a Jira fix version, an
Asana section.

```kotlin
@Serializable
data class ProviderGroup(
    val provider: String,
    val groupId: String,
    val name: String,
    val order: Int? = null,
    val members: Set<NodeUri> = emptySet(),
)
```

`NodeMutation` is a sealed interface with one member per mutation the app offers today:
`SetAssignee`, `SetState`, `SetPriority`, `SetEstimate`, `RemoveFromProject`, `AttachLink`,
`CreateRelation`, `DeleteRelation`. A provider that lacks the capability returns
`MutationResult.Unsupported`. The UI reads `capabilities` and greys the row before the user
clicks. It never sends a mutation it knows will fail.

### 2.2 Scope

`FilterOptions` is Linear-shaped: `team`, `project`, `projectId`, `label`, `cycleId`. Other
trackers do not share those words.

The lazy answer for now: keep `FilterOptions` as the Linear scope, and give `ProviderScope` one
field per provider.

```kotlin
@Serializable
data class ProviderScope(val provider: String, val filters: FilterOptions)
```

That is honest while Linear is the only tracker. See open question 14.

### 2.3 Auth

`TokenStore` names its two providers today: `getLinear`, `setLinear`, `getGithub`, `setGithub`.
`KeyValueTokenStore` already stores each one under a string key. Add a keyed pair and keep the
named methods as wrappers.

```kotlin
interface TokenStore {
    fun get(providerId: String): String?
    fun set(providerId: String, value: String)
    fun clear(providerId: String)
    // The Linear and GitHub methods stay. They call the three above.
}
```

A provider declares what it needs:

```kotlin
sealed interface ProviderAuth {
    data class OAuth(val authorizeUrl: String, val tokenUrl: String, val scopes: List<String>) : ProviderAuth
    data class DeviceFlow(val codeUrl: String, val tokenUrl: String, val scopes: List<String>) : ProviderAuth
    data object ApiKey : ProviderAuth
    data object None : ProviderAuth
}
```

`ProviderAuth.None` is what the built-in link provider uses.

### 2.4 The registry

One object holds every provider. Nothing else builds a client.

```kotlin
class ProviderRegistry(
    val work: List<WorkProvider>,
    val topology: List<TopologyProvider>,
) {
    fun work(id: String): WorkProvider? = work.firstOrNull { it.id == id }
    fun topology(id: String): TopologyProvider? = topology.firstOrNull { it.id == id }
}
```

`GraphSession` takes the registry instead of `LinearClient` and `GithubClient`. `SwimApp.kt` and
`cli/Runtime.kt` build the registry the same way. The CLI and the app then run the same providers,
which is the rule the spec already states in section 5b.

### 2.5 The first implementations

- `LinearWorkProvider` wraps `LinearClient`. `LinearClient` does not change. The provider maps
  `getIssuesWithRelations` onto `nodes` plus `relations`, and maps `NodeMutation` onto the
  existing suspend calls.
- `GithubTopologyProvider` wraps `GithubClient`. `getPrStatuses` becomes `topology`.
- `LinkProvider` is a built-in `WorkProvider`. It reads its nodes from the Swim overlay store, not
  from a network. Capabilities: `READ_NODES` only. It serves bare links and notes.

Each wrapper is thin. The network code stays where it is.

## 3. The Swim overlay store

The overlay holds what the user adds. It writes to no external service.

### 3.1 Shapes

```kotlin
@Serializable
data class SwimRelation(
    val id: String,
    val from: NodeUri,
    val to: NodeUri,
    val type: RelationType,
    val note: String? = null,
)

@Serializable
data class GroupBacking(val provider: String, val groupId: String)

@Serializable
data class SwimGroup(
    val id: String,
    val name: String,
    /** Provider groups this group takes its members from. Empty means an unbacked group. */
    val backing: List<GroupBacking> = emptyList(),
    /** Members the user added by hand. */
    val added: Set<NodeUri> = emptySet(),
    /** Members the user removed, even though a backing supplies them. */
    val removed: Set<NodeUri> = emptySet(),
    val order: Int? = null,
)

/** A bare link or a note. The link provider reads these. */
@Serializable
data class SwimNode(
    val uri: NodeUri,
    val title: String,
    val url: String? = null,
    val body: String? = null,
)

@Serializable
data class SwimOverlay(
    val relations: List<SwimRelation> = emptyList(),
    val groups: List<SwimGroup> = emptyList(),
    val nodes: List<SwimNode> = emptyList(),
)
```

A merge of two groups is one `SwimGroup` with two entries in `backing`. It needs no separate type.

### 3.2 Where it lives

Follow the `PositionStore` precedent exactly.

```kotlin
interface OverlayStore {
    fun get(): SwimOverlay
    fun set(overlay: SwimOverlay)
}

const val OVERLAY_KEY: String = "swim.overlay"
```

- `SettingsOverlayStore` writes one JSON string, like `SettingsPositionStore`.
- `SafeOverlayStore` logs a failed write, like `SafePositionStore`.
- Desktop and the CLI use a file store, like `FilePositionStore`. The settings backing caps one
  value at 8 KB, and an overlay grows past that faster than a position snapshot does.

The overlay is user data, not a cache. A failed read must not delete it. `decodeSnapshot` returns
an empty snapshot on a parse failure, which is right for positions and wrong here. `OverlayStore`
must keep the bad text and report the failure instead. See open question 12.

### 3.3 How the overlay reaches the graph

Add one pure function beside `PrRelations.kt`.

```kotlin
fun withOverlay(data: GraphData, overlay: SwimOverlay): GraphData
```

`GraphSession.loadAndFollow` then runs three steps in this order:

1. The work provider answers with nodes and relations.
2. `withPrRelations` adds the relations the branch topology implies.
3. `withOverlay` adds the swim relations and the swim nodes.

The overlay runs last. The user layer must never be dropped by a derivation pass.

### 3.4 Conflict rules

The provider data is the base layer. The user layer sits on top of it. It never replaces it.

| Case | Rule |
|---|---|
| A provider relation and a swim relation join one pair | Both stay. They carry a different provenance. |
| A swim group removes a member a provider group supplies | The member leaves the group. The node stays in the graph. |
| A node belongs to two swim groups | The group with the lowest `order` wins. Grouping draws one area per node. |
| A backing group disappears from the provider | The swim group stays, with its `added` members only. |
| An `added` or `removed` entry names a node the graph does not hold | The entry stays on disk. It costs nothing. Prune it on the next write. |
| The user attaches a backing to an unbacked group | The provider members join. The `removed` set still applies. |

The derived pull-request block already drops when a Linear `blocks` relation joins the same pair.
Whether a swim block does the same is open question 4.

## 4. Edge and group provenance

### 4.1 The edge

`EdgeProvenance` has two entries today: `LINEAR` and `PR_DERIVED`. It grows to three, and the
provider id moves to its own field.

```kotlin
@Serializable
enum class EdgeProvenance {
    /** A relation the provider holds. Only this one has a relation the provider can delete. */
    @SerialName("provider") PROVIDER,

    /** A relation Swim computed from provider data, such as a pull-request stack. */
    @SerialName("derived") DERIVED,

    /** A relation the user made in Swim. It has no external effect. */
    @SerialName("swim") SWIM,
}

@Serializable
data class IssueEdge(
    val from: NodeUri,
    val to: NodeUri,
    val type: RelationType,
    val relationId: String? = null,
    val provenance: EdgeProvenance = EdgeProvenance.PROVIDER,
    /** Which provider. Which derivation rule. Empty for a swim relation. */
    val source: String = "linear",
)
```

No store holds an `IssueEdge`, so the rename needs no data migration.

### 4.2 What the UI keys on

| Provenance | Edge style | Card menu row | Edge click |
|---|---|---|---|
| `PROVIDER` | Solid, full alpha. | Change and Remove, if the provider has `WRITE_RELATIONS`. | The change chooser. |
| `DERIVED` | Solid, `DERIVED_ALPHA` of 0.55. | One read-only row. | The info panel with `derivedEdgeLines`. |
| `SWIM` | Open question 5. | Change and Remove. The rows act on the overlay. | The change chooser, marked "Swim only". |

`ContextMenu.isDerived(key)` becomes `ContextMenu.provenanceOf(key)`. `menuEntries` then branches
three ways instead of two. `GraphCanvas.kt` line 888 branches on the same value.

### 4.3 The group

A group carries the same three-way provenance.

```kotlin
enum class GroupProvenance { PROVIDER, DERIVED, SWIM }
```

`PROVIDER` is a group the tracker owns and the user has not edited. `SWIM` is a group with an edit
or with no backing. `DERIVED` is reserved: no rule makes one today. The group label draws the
provenance, so the user can see which areas Swim owns.

## 5. Terminology: milestone becomes Swim group

The word "milestone" leaves every user-facing string. It stays only in the Linear wire code, where
it is Linear's own field name.

| Place | Change |
|---|---|
| `FilterStore.kt` `GraphGrouping.MILESTONE` | Rename to `GROUP`. Keep `@SerialName("MILESTONE")`. |
| `FilterStore.kt` `resolveGrouping` | Read group membership from the projected overlay, not from `node.milestone`. |
| `Analysis.kt` `GroupBy.MILESTONE` and `groupIssues` | Rename. The `"No milestone"` bucket becomes `"No group"`. |
| `Placement.kt` `groupKeyOf`, `UNGROUPED` | Same rename. The bucket text changes. |
| `GraphScreen.kt` line 799 | The option `"milestone"` / `"Milestone"` becomes `"group"` / `"Group"`. |
| `GraphScreen.kt` line 520 | `"Cross-milestone links"` becomes `"Cross-group links"`. |
| `IssueNode.kt` `milestone`, `milestoneId` | See open question 9. |
| `LinearWire.kt`, `LinearQueries.kt` | No change. These are Linear's field names. |
| `docs/spec.md`, `docs/tasks/core/0011-milestone-sort-order.md` | Update the prose. |
| `desktopApp/.../Shot.kt` | Rename the screenshot files and the `groupBy` argument. |
| `PlacementTest.kt`, `GroupLabelDragTest.kt`, `GroupingTest.kt`, `FilterStoreTest.kt` | Rename. 48 references live in `PlacementTest.kt` alone. |

### 5.1 The one persistence risk

`cacheKey` encodes `GraphGrouping` by name into every saved layout key. `groupingOf` decodes it.
`FilterStore` persists the same enum. Renaming `MILESTONE` to `GROUP` without a serial name would
invalidate every saved milestone layout.

Keep `@SerialName("MILESTONE")` on `GROUP`. That is one line, and it makes the rename free. See
open question 10.

## 6. The plugin longview: the seams only

This design does not build a plugin system. It leaves these seams open, so a plugin fits later
without a rework.

1. **One lookup.** `ProviderRegistry` is the only way to reach a provider. Today `SwimApp.kt` and
   `cli/Runtime.kt` both build clients by hand. After phase P9 they build a registry instead.
2. **String ids, not enums.** The provider id, the node kind and the derivation rule are all
   strings. A new provider needs no change in `:core`.
3. **Capability sets, not booleans.** An unknown capability is ignorable. An old build reads a new
   provider without a crash.
4. **The load-then-follow shape.** `GraphSession.loadAndFollow` already loads once and then
   follows pull-request refreshes. An agent pipeline that pushes status updates needs a
   `Flow<ProviderEvent>` beside `nodes()`. The block shape is ready. Do not build the flow now.
5. **The overlay is provider-agnostic.** A `SwimRelation` joins two `NodeUri` values. It never
   asks which provider they came from. A plugin node carries swim relations on day one.
6. **Tolerant reads.** Every overlay read and config read sets `ignoreUnknownKeys`. An old build
   reads a file a new plugin wrote.
7. **No Compose in `:core`.** The existing contract already means a provider ships with no UI.

Not designed here: plugin discovery, plugin sandboxing, plugin versioning, plugin distribution,
and the agent-pipeline event schema.

## 7. Phased migration

Each phase is one agent brief. The app works after every phase.

### P6 — Node identity and provenance

Core only. No behaviour change and no new UI.

- Add `NodeUri` in `core/src/commonMain/kotlin/swim/core/model/NodeUri.kt`.
- Add `IssueNode.uri` with the derived default.
- Change `EdgeProvenance` to `PROVIDER`, `DERIVED`, `SWIM`. Add `IssueEdge.source`.
- Update `ContextMenu.kt` and `GraphCanvas.kt` to branch on the new enum.
- Make `decodeSnapshot` parse legacy keys and `encodeSnapshot` write URIs.
- Update `CLAUDE.md`: the graph key is a `NodeUri`, and `linear:issue/ENG-123` is the example.

Done when: the desktop app loads a real workspace, and a saved milestone layout still opens.

### P7 — The overlay store and swim relations

- Add `SwimOverlay`, `OverlayStore`, `SettingsOverlayStore`, `SafeOverlayStore`, and a
  `FileOverlayStore` in `jvmMain`.
- Add `withOverlay` beside `PrRelations.kt`.
- Add `GraphSession.createSwimRelation`, `changeSwimRelation`, `removeSwimRelation`. These write
  the overlay and re-project. They send no network request.
- Wire the store in `SwimApp.kt` and `cli/Runtime.kt`.
- UI: the relation drag handle offers "Swim relation" beside the provider relation. The card menu
  and the edge panel act on a `SWIM` edge.

Done when: the user marks A swim-blocked by B, the layout moves, and Linear shows no change.

### P8 — Swim groups and the rename

- Add `SwimGroup` and `GroupBacking` to the overlay.
- Derive the group set from the provider groups, then apply the merges, the `added` set and the
  `removed` set.
- Rename `MILESTONE` to `GROUP` with the serial name kept.
- Change every user-facing string per section 5.
- UI: the area label offers Rename, Merge, and Detach. The card menu offers "Move to group".

Done when: the user merges two milestone areas into one named group, and the graph redraws.

### P9 — The provider interfaces

- Add `WorkProvider`, `TopologyProvider`, `ProviderCapability`, `ProviderGroup`, `NodeMutation`,
  `ProviderRegistry`, `ProviderAuth`.
- Add `LinearWorkProvider` and `GithubTopologyProvider` as thin wrappers.
- Change `GraphSession` to take the registry.
- Change `TokenStore` to the keyed form.
- Change `SwimApp.kt` and `cli/Runtime.kt` to build a registry.

Done when: the app and the CLI both run through the registry, and every test still passes.

### P10 — The link and note provider

- Add `LinkProvider`. It reads `SwimOverlay.nodes`.
- UI: paste a URL onto the canvas to make a link node. Add a note node from the canvas menu.
- The card for a link node is a small card. It has no state and no assignee.

Done when: a note node swim-blocks a Linear issue, and the layout respects the block.

### P11 — A second tracker and a second host

- Add one more `WorkProvider` and one more `TopologyProvider`.
- This phase proves the seams. It also finds the places where `FilterOptions` leaks Linear words.

Done when: one graph draws nodes from two trackers.

## 8. Open questions

The owner must rule on each one.

1. **URI text form.** `linear:issue/ENG-123`, or `swim://linear/issue/ENG-123`? The first is
   shorter and reads well in a JSON key. The second is a real URI and parses with a standard tool.
2. **Legacy position keys.** Read bare identifiers through `NodeUri.parse` forever, or run one
   rewrite pass and drop the fallback after a release?
3. **Two edges, one pair.** A provider `blocks` and a swim `blocks` can join the same two nodes.
   Draw both with an offset, draw one with a badge, or refuse the second?
4. **Swim block against a derived block.** A derived pull-request block drops when a Linear
   `blocks` relation exists. Should a swim `blocks` relation suppress it the same way?
5. **Swim edge style.** The spec rejected a dashed `blocks` edge. What draws a swim `blocks` edge?
   A dotted line, a glyph on the line, or a different colour?
6. **Analysis or layout only.** The vision says a swim relation affects the layout. Does it also
   affect `findReadySet`, `findBlockerChain` and the CLI `ready`, `next` and `blockers` commands?
   A yes changes what the CLI reports to an agent.
7. **Overlay location.** Positions live in the app settings and in a CLI config file. Should the
   app and the CLI share one overlay file, so a swim relation made in the app reaches the CLI?
8. **Two groups, one node.** Lowest `order` wins is the proposal. Is that right, or must the UI
   refuse the second membership?
9. **Field names.** Does the rename change `IssueNode.milestone` and `IssueNode.milestoneId` to
   `providerGroupName` and `providerGroupId`, or does it change only user-facing text?
10. **Serial name.** Keep `@SerialName("MILESTONE")` on `GraphGrouping.GROUP` forever, or ship one
    key migration and remove it?
11. **`IssueNode` name.** Rename it to `WorkNode` in P6, rename it later, or never? The vision says
    a node is work, not a task. The rename touches every UI file and every test.
12. **A bad overlay read.** `PositionStore` returns empty on a parse failure. The overlay is user
    data. Should a failed read block the app, or load empty and warn?
13. **Write-back.** The user attaches a Linear milestone to a swim group. Does Swim then write the
    membership back to Linear, or does the backing stay read-only?
14. **Scope model.** `FilterOptions` is Linear-shaped. Keep one Linear-shaped scope per provider,
    make a union type, or make each provider parse a scope string of its own?
