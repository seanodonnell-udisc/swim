---
id: 0001
title: Layout playground
area: layout
status: done
priority: P1
depends_on: []
created: 2026-09-01
tags: [layout]
---
## Goal
Build a Compose desktop window with hot reload to hand-iterate the tidy blocker-tree algorithm.

## Why
The layout algorithm is user-designed, not a port. The user needs a fast loop to try
placement rules against sample graphs before the algorithm is final.

## Acceptance
- [x] A desktop window shows a sample graph laid out by `swim.layout.layout`.
- [x] Compose Hot Reload works, so a code change updates the window without a restart.
- [x] At least one sample graph ships with the playground.

## Notes
See `docs/spec.md` §6 for the tidy blocker-tree rules this playground is for.

`:layout` is one file per step: `cycles.kt`, `layering.kt`, `forest.kt`, `position.kt`,
`pack.kt`, `affinity.kt`. Rewrite `position.kt` alone to change the placement rule.
