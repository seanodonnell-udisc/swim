---
id: 0012
title: Stop layout inheritance across grouping modes
area: core
status: todo
priority: P3
depends_on: []
created: 2026-09-02
tags: [position-cache]
---

A grouped view no longer inherits a flat layout. The reverse hole stays open.
A new ungrouped query can inherit positions from a grouped cache entry.
Make the grouping readable from the cache key in `swim.core.session.cacheKey`. Then `reuseAndPlace` can skip entries with a different grouping.
