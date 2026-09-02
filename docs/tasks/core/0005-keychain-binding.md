---
id: 0005
title: Bind Security.framework for the desktop keychain
area: core
status: todo
priority: P2
depends_on: []
created: 2026-09-02
tags: [auth, desktop, keychain]
---
## Goal
The desktop app reads the keychain item that the CLI wrote. The user sees no prompt.

## Why
`JvmTokenStore` runs `/usr/bin/security`. macOS gives each binary its own access to a keychain
item. The first read from a new binary shows a GUI authorization prompt. The CLI and the desktop
app therefore do not share one credential in practice.

## Acceptance
- [ ] `JvmTokenStore` calls Security.framework. It does not run `/usr/bin/security`.
- [ ] The desktop app reads the CLI token. macOS shows no prompt.
- [ ] The file fallback stays available with `-Dswim.insecureStorage=true`.
- [ ] A test covers a missing item and a read error.

## Notes
Use JNA, JNI, or the Java FFM API. The functions are `SecItemCopyMatching` and `SecItemAdd`.
Until this task is done, the file fallback and the one time prompt are the only paths.
See `docs/spec.md` §4.
