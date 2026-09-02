---
id: 0007
title: Close the desktop log file to other users
area: ui
status: todo
priority: P2
depends_on: []
created: 2026-09-02
tags: [security, desktop, logging]
---
## Goal
The desktop log file is readable by its owner only.

## Why
`Log` writes `swim-desktop.log` beside the config with `File.appendText`. That call uses the
default mode, which is 0644 on most systems. The file holds the stack trace of every uncaught
exception. The directory also holds `tokens.json` when the file fallback is in use.

`writePrivateFile` now sets mode 0700 on that directory. The log file itself still has no
explicit mode, so `Log` must set one.

## Acceptance
- [ ] `Log` creates the file with mode 0600. It sets the mode again after a rotation.
- [ ] The rotated file `swim-desktop.log.1` has mode 0600.
- [ ] A test asserts both modes.

## Notes
`desktopApp/src/main/kotlin/swim/desktop/Log.kt:13` and `:32`. Reuse
`swim.core.config.writePrivateFile`, or call `Files.setPosixFilePermissions` in `start()`.
See `docs/security.md`.
