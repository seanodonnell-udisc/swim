---
id: 0004
title: Register the Linear and GitHub OAuth apps
area: distribution
status: todo
priority: P1
depends_on: []
created: 2026-09-02
tags: [auth, oauth, release]
---
## Goal
`swim auth` completes the OAuth flows. The user does not need a personal API key.

## Why
`swim.core.auth.OAuthApps` holds two placeholder client ids. No OAuth app exists yet.
`swim auth` therefore stops with an error and tells the user to run `swim auth --key <apiKey>`.
The GitHub device flow is skipped in the same way. Pull-request status stays absent.

## Acceptance
- [ ] A Linear OAuth app exists. The name does not contain the word "Linear".
- [ ] The Linear app manifest sets `distribution: public`.
- [ ] The Linear app allows PKCE and the scopes `read,write`.
- [ ] The Linear app lists both redirect URIs: `http://127.0.0.1:8976/callback` for the CLI and
      the desktop app, and the GitHub Pages callback for mobile.
- [ ] A GitHub OAuth app exists with device flow turned on.
- [ ] `OAuthApps.LINEAR_DEFAULT` and `OAuthApps.GITHUB_DEFAULT` hold the real client ids.
- [ ] `swim auth` signs in to Linear through the browser and stores the tokens.
- [ ] `swim auth` connects GitHub through the device flow.

## Notes
Linear rejects an app name that contains "Linear".
The loopback port is FIXED at 8976. The CLI and the desktop app both bind it, and the
registered redirect URI names it. See `OAuthApps.LOOPBACK_PORT`.
GitHub gives no read-only private scope. The `repo` scope is the only one that reads private
pull requests. Say this to the user before the device flow starts.
Both ids can be overridden without a rebuild: set `SWIM_LINEAR_CLIENT_ID` or
`SWIM_GITHUB_CLIENT_ID`. Use these to test a new registration.
