---
id: 0008
title: Add a sign-out command to the CLI
area: core
status: todo
priority: P2
depends_on: []
created: 2026-09-02
tags: [auth, cli, security]
---
## Goal
`swim auth --sign-out` revokes the Linear token and clears every stored credential.

## Why
The desktop app can sign out. `GraphScreen.signOut` revokes the OAuth token, then calls
`clearLinear` and `clearGithub`. The CLI has no equal command.

A CLI user who wants to remove a credential must run `security delete-generic-password` by
hand. That leaves the token live at Linear, because nothing calls the revoke endpoint.

## Acceptance
- [ ] `swim auth --sign-out` calls `LinearOAuth.revoke` when the mode is `OAUTH`.
- [ ] A revoke that fails does not stop the command. The command still clears the store.
- [ ] The command clears the Linear token and the GitHub token.
- [ ] The command prints one confirmation line to stderr.

## Notes
`cli/src/commonMain/kotlin/swim/cli/commands/Auth.kt`. Copy the order from
`shared/src/commonMain/kotlin/swim/ui/app/GraphScreen.kt:579`. App Store guideline 5.1.1(v)
requires the revoke call. See `docs/spec.md` §4 and `docs/security.md`.
