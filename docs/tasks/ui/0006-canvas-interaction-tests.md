---
id: 0006
title: Add pointer tests for the canvas panels
area: ui
status: todo
priority: P3
depends_on: []
created: 2026-09-02
tags: [test, canvas]
---
## Goal
Tests drive the canvas panels with a pointer. Each panel gets proof that it works.

## Why
The confirm dialog, the relation chooser, and the URL field states need a pointer. The offscreen
renderer in `desktopApp/src/main/kotlin/swim/desktop/Shot.kt` has no synthetic input. These parts
have unit tests only where the logic is pure. Nobody has seen the error banner or the overflow
menu in a shot.

## Acceptance
- [ ] A test opens the confirm dialog and presses the confirm button.
- [ ] A test drags a link handle onto another card and picks a relation.
- [ ] A test shows the URL field in the resolving state and in the error state.
- [ ] A test shows the error banner and the overflow menu.
- [ ] The tests run in `:shared:jvmTest`.

## Notes
Use `runComposeUiTest` from the `compose.uiTest` artifact. It supplies synthetic pointer input.
`ImageComposeScene` also accepts `sendPointerEvent`. Use it if a test must write a PNG.
