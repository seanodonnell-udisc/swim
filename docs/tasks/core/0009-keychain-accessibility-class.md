---
id: 0009
title: Set the keychain accessibility class explicitly
area: core
status: todo
priority: P3
depends_on: [0005]
created: 2026-09-02
tags: [auth, keychain, security]
---
## Goal
Every Swim keychain item carries an explicit `kSecAttrAccessible` value.

## Why
`KeychainTokenStore` uses `KeychainSettings(KEYCHAIN_SERVICE)`, which sets `kSecClass` and
`kSecAttrService` only. Apple then applies its default, `kSecAttrAccessibleWhenUnlocked`.
That default is correct today, but no code states it.

Swim cannot set the value through this library. `KeychainSettings.keyChainOperation` merges its
default properties into every dictionary. `SecItemCopyMatching`, `SecItemDelete` and
`SecItemUpdate` would then filter on the attribute. Reads of items that `/usr/bin/security`
wrote would fail, and the CLI and the app would stop sharing one item.

Task #0005 replaces the library with a direct Security.framework binding. That binding can put
the attribute in the insert dictionary and leave it out of the query.

## Acceptance
- [ ] `SecItemAdd` sends `kSecAttrAccessible`. The query dictionaries do not send it.
- [ ] The CLI and the desktop app still read one shared item.
- [ ] The team records why it chose the class. Compare `WhenUnlocked` with
      `WhenUnlockedThisDeviceOnly`, which blocks a restore onto another device.

## Notes
See `docs/security.md`, section "Accepted limits".
