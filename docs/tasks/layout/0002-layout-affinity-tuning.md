---
id: 0002
title: Layout affinity tuning
area: layout
status: todo
priority: P2
depends_on: [0001]
created: 2026-09-02
tags: [layout]
---
## Goal
Tune the related-edge affinity heuristic in the playground.

## Why
Related edges are a soft pull, not structure. The starting heuristic orders siblings and
trees by index distance only. The right weight and the right cost model come from looking
at real graphs in the playground.

## Acceptance
- [ ] The affinity slider gives a useful range on the sample graphs.
- [ ] The cost model accounts for node widths, or the index-distance model is confirmed enough.
- [ ] `affinity.kt` states the chosen model in one line.

## Notes
`affinity.kt` holds the whole heuristic. The cost of an order is
`weight * sum(pairWeight * slots between the pair)` plus one per slot each item moved from
its input index, searched by adjacent-swap hill climbing.
