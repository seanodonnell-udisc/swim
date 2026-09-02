---
id: 0003
title: Support the native duplicate workflow state
area: core
status: todo
priority: P3
depends_on: []
created: 2026-09-02
tags: [linear-schema]
---

Linear added a `duplicate` workflow state type. The client maps it to `CANCELED`.
Add a `DUPLICATE` enum value. Treat it as done in the analysis. Hide these issues with the duplicates toggle.
Linear also added a `similar` relation type. The client drops it. Decide if the graph shows it.
