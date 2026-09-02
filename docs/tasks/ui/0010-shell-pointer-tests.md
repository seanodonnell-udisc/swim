---
id: 0010
title: Add pointer tests for the app shell surfaces
area: ui
status: todo
priority: P3
depends_on: [0006]
created: 2026-09-02
tags: [test, shell]
---
## Goal
Tests drive the surfaces in `GraphScreen` with a pointer, the way #0006 drives the canvas.

## Why
Task #0006 closed the canvas half. Four surfaces are left. All four sit in `swim.ui.app`, above
the canvas: the confirm dialog before every Linear mutation, the Linear URL field in its
resolving state and its error state, the error banner, and the overflow menu. Nobody has seen
these surfaces in a shot. Unit tests cover their logic only where that logic is pure.

## Acceptance
- [ ] A test opens the confirm dialog and presses the confirm button.
- [ ] A test shows the URL field in the resolving state and in the error state.
- [ ] A test shows the error banner and the overflow menu.
- [ ] The tests run in `:shared:jvmTest`.

## Notes
The fixture is the blocker, not the pointer. `GraphScreen` takes a `SwimSession`. That session
needs a `LinearClient`, a `GithubClient`, a `FilterStore` and a `PositionStore`. Build the two
clients on a Ktor `MockEngine`, as the `core` tests do. Use a `MapSettings` for the two stores.

The pointer part is solved. Copy the `move`, `click` and `rightClick` helpers from
`CanvasInteractionTest`. Copy the two API notes in #0006 as well.
