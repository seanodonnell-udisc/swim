---
name: swim
description: Query a Linear workspace from the command line. Use this skill to list issues, find what blocks an issue, find what an issue unblocks, rank ready work, report team progress, or find stale issue names in source code.
---

# swim

`swim` reads a Linear workspace. Use it instead of the Linear web app for any question
about issues, blocker chains, or team progress.

## Check the sign-in first

Run `swim teams`. Exit code 0 means the CLI is signed in.
Exit code 1 means nobody signed in yet.
Tell the user to run `swim auth`, or `swim auth --key <apiKey>`.
Never run `swim auth` for the user. The command opens a browser.

## Always give a scope

`list`, `status`, `ready`, `next`, and `comment-cleanup` need a scope.
Give a Linear URL, or one of `-t/--team`, `-p/--project`, `-l/--label`,
`--cycle`, or `--assignee`.
Without a scope the command stops with exit code 2.
Run `swim teams` first if you do not know the team key.

Add `--include-completed` to see finished work.
Add `--exclude-label <name>` to drop noise.

## Always add --json

Add `--json` to every command that you parse.
Every payload has the same four fields:

```json
{ "command": "list", "scope": {}, "count": 12, "data": [] }
```

Read the results from `data`. The `count` field is absent for `show`.
Read only stdout. stderr carries progress notes, not results.

## Read the exit code

| Code | Action |
|---|---|
| 0 | Use the output |
| 1 | Report the stderr message to the user |
| 2 | Correct the options, then run the command again |
| 3 | Tell the user the issue does not exist |

## Pick the command

- `swim list [url]` — the issues in scope. Add `--group-by state` or `--sort updated`.
- `swim show <issue>` — one issue with its relations and pull requests.
- `swim teams`, `swim projects`, `swim labels` — reference data for the scope options.
- `swim status [url]` — progress per team, plus the blocks that cross teams.
- `swim ready [url]` — the issues that can start now.
- `swim next [url]` — the ready issues ranked by value. Add `-n <count>`.
- `swim blockers <issue>` — everything that blocks one issue.
- `swim downstream <issues...>` — everything that finishing the issues unblocks.
- `swim relate <from> <type> <to>` — create one relation.
- `swim bulk-relate <type> <from...> --to <issue>` — create many relations.
- `swim comment-cleanup [url]` — code comments that name done or unknown issues.
- `swim refs <issue>` — the places where the code names one issue.

## Ask before you write

`relate` and `bulk-relate` change the Linear workspace.
Show the user the exact command first. Wait for approval.
Every other command only reads.

## Worked examples

Plan a sprint for two teams:

```
swim next -t ENG,WEB -n 10 --json
```

Explain why one issue cannot start:

```
swim blockers ENG-123 --json
```

Measure the value of finishing an issue:

```
swim downstream ENG-123 --json
```

Draw a diagram of a blocker chain:

```
swim blockers ENG-123 --mermaid
```
