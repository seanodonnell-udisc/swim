---
id: 0006
title: Add pointer tests for the canvas panels
area: ui
status: done
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
- [x] A test drags a link handle onto another card and picks a relation.
- [ ] A test shows the URL field in the resolving state and in the error state.
- [ ] A test shows the error banner and the overflow menu.
- [x] The tests run in `:shared:jvmTest`.

## Notes
Use `runComposeUiTest` from the `compose.uiTest` artifact. It supplies synthetic pointer input.
`ImageComposeScene` also accepts `sendPointerEvent`. Use it if a test must write a PNG.

`ImageComposeScene.sendPointerEvent` was enough. `compose.uiTest` was not added.

`shared/src/jvmTest/kotlin/swim/ui/graph/CanvasInteractionTest.kt` holds ten tests. They cover
the relation chooser after a handle drag, all three context menus, the assign submenu, the
pick-target relation flow, Esc, the drag threshold that keeps a right drag from opening a menu,
the shortcuts overlay, and the zoom readout. Three of them write a PNG to
`shared/build/reports/`.

Two facts the next person needs:
- Build a key event with `KeyEvent(key, type, isShiftPressed)` from `@InternalComposeUiApi`.
  The desktop `NativeKeyEvent` is `Any` and holds an internal type, so a `java.awt.event.KeyEvent`
  throws `ClassCastException` inside the scene.
- Compare pixels with `Color.toArgb()`. `Color.value.toInt()` keeps only the low 32 bits, which
  for an sRGB colour is the colour space id, so every pixel looks the same.

## Residual
The three unchecked boxes are in the app shell, not in the canvas. Each one needs a
`SwimSession`. A `SwimSession` needs a `LinearClient` and a fake transport. Task #0010 carries
them: the confirm dialog, the two URL field states, the error banner, and the overflow menu.
