# Swim task tracker

This is a file-based task tracker. Each task is one markdown file.

## Layout

```
docs/tasks/
  <area>/       # open tasks, grouped by subsystem
  completed/
    <area>/     # finished tasks, mirroring the area folders
```

The `tasks/` tree is the index. List the area folders for an overview.

## Task file

Filename: `NNNN-kebab-title.md` (a 4-digit, zero-padded id).

Frontmatter:

```markdown
---
id: 0001
title: Short task title
area: layout
status: todo          # todo | active | done
priority: P1          # P0 highest .. P3 lowest
depends_on: []        # ids that should land first, e.g. [0001]
created: 2026-09-01
tags: []
---
## Goal
One sentence: what shipping this produces.
## Why
Motivation.
## Acceptance
- [ ] Checkboxes that define done.
## Notes
Design notes and gotchas.
```

## Lifecycle

1. **Create.** Set the new id to the highest id in `tasks/` and `completed/`, plus one.
   Put the file in `tasks/<area>/`. Set status to `todo`.
2. **Start.** Set status to `active`. Keep only a few tasks active at once.
3. **Complete.** Check the acceptance boxes. Set status to `done`.
   Move the file to `tasks/completed/<area>/` with `git mv`.
4. **Commit.** Cite the task id as `#NNNN` in the commit message.

## Conventions

- Task ids are permanent. Never reuse or renumber an id.
- Reference tasks in prose and commits as `#NNNN`.
