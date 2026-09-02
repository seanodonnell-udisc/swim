# swim

`swim` reads a Linear workspace from the command line.
It shows issues, blocker chains, and progress per team.
The output is stable. Agents are the primary users.

## Sign in first

Run `swim auth` one time.
The command stores the credentials in the login keychain.
Run `swim auth --key <apiKey>` to sign in with a personal API key.
GitHub is optional. Without GitHub, pull-request status is absent.

## Scope

A command that reads a set of issues needs a scope.
Give a Linear URL, or one of the options below.
A command without a scope stops with exit code 2.
These commands take a scope: `list`, `status`, `ready`, `next`, `comment-cleanup`.

| Option | Effect |
|---|---|
| `[url]` | Set the scope from a Linear URL |
| `-t, --team <keys>` | Select teams, comma-separated |
| `-p, --project <name>` | Select one project |
| `-l, --label <name>` | Select one label |
| `--exclude-label <name>` | Remove issues with this label |
| `--priority <n>` | Select priority 0 to 4 |
| `--state <name>` | Match part of a state name |
| `--state-type <types>` | Select state types, comma-separated |
| `--assignee <name>` | Match part of an assignee name |
| `--cycle <id>` | Select one cycle |
| `--include-completed` | Add completed and canceled issues |

## Output

Add `--json` for machine-readable output.
Every payload has the same shape:

```json
{ "command": "list", "scope": {}, "count": 12, "data": [] }
```

The `count` field is absent for `show`.
stdout carries only results. stderr carries only progress notes.
Colour is absent when you pipe the output.
Add `--mermaid` to `status`, `blockers`, or `downstream` for a diagram.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The command did the work |
| 1 | An error stopped the command |
| 2 | Bad usage, or an unknown scope |
| 3 | The issue does not exist |

## Commands

- `swim auth` — Sign in to Linear. Connect GitHub.
- `swim list [url]` — List the issues in scope.
- `swim show <issue>` — Show one issue with its relations.
- `swim teams` — List every team.
- `swim projects` — List every project.
- `swim labels` — List every label.
- `swim status [url]` — Show progress and cross-team blocks.
- `swim ready [url]` — List the issues that can start now.
- `swim next [url]` — Rank the ready issues by value.
- `swim blockers <issue>` — Show everything that blocks one issue.
- `swim downstream <issues...>` — Show what finishing the issues unblocks.
- `swim relate <from> <type> <to>` — Create one relation.
- `swim bulk-relate <type> <from...> --to <issue>` — Create many relations.
- `swim comment-cleanup [url]` — Find stale issue names in code.
- `swim refs <issue>` — Show where the code names one issue.

## Examples

Find the best work to start in two teams:

```
swim next -t ENG,WEB -n 5 --json
```

Find out why one issue cannot start:

```
swim blockers ENG-123 --json
```

Measure the value of finishing an issue:

```
swim downstream ENG-123 --json
```
