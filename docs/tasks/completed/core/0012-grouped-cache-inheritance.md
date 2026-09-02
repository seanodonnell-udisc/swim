---
id: 0012
title: Stop layout inheritance across grouping modes
area: core
status: done
priority: P3
depends_on: []
created: 2026-09-02
tags: [position-cache]
---

A grouped view no longer inherits a flat layout. The reverse hole stays open.
A new ungrouped query can inherit positions from a grouped cache entry.
Make the grouping readable from the cache key in `swim.core.session.cacheKey`. Then `reuseAndPlace` can skip entries with a different grouping.

## Done
`swim.core.session.groupingOf(key)` decodes the grouping back out of a cache key, and returns
null for a key that did not come from `cacheKey`.

`swim.ui.app.placeGraph` filters the donor keys before `reuseAndPlace` sees them: a key donates
only when its grouping equals the current key's. `reuseAndPlace` itself is unchanged, so
`:layout` keeps no opinion about groupings. Same-grouping donation still runs, which is the
intended feature.

Tests: `PositionStoreTest.theGroupingIsReadableBackOffTheCacheKey`,
`PlacementTest.eachGroupingKeepsItsOwnArrangement` (both directions), and
`PlacementTest.aSiblingOfTheSameGroupingStillDonates`.
