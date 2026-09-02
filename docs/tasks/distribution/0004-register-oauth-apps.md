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
`swim.cli.OAuthApps` holds two placeholder client ids. No OAuth app exists yet.
`swim auth` therefore stops with an error and tells the user to run `swim auth --key <apiKey>`.
The GitHub device flow is skipped in the same way. Pull-request status stays absent.

## Acceptance
- [ ] A Linear OAuth app exists. The name does not contain the word "Linear".
- [ ] The Linear app manifest sets `distribution: public`.
- [ ] The Linear app allows PKCE and the scopes `read,write`.
- [ ] The Linear app lists both redirect URIs: the loopback `http://127.0.0.1/callback`
      form for the CLI and the desktop app, and the GitHub Pages callback for mobile.
- [ ] A GitHub OAuth app exists with device flow turned on.
- [ ] `OAuthApps.LINEAR_DEFAULT` and `OAuthApps.GITHUB_DEFAULT` hold the real client ids.
- [ ] `swim auth` signs in to Linear through the browser and stores the tokens.
- [ ] `swim auth` connects GitHub through the device flow.

## Notes
Linear rejects an app name that contains "Linear".
Linear accepts a loopback redirect URI on any port, so the CLI can pick a free port each time.
GitHub gives no read-only private scope. The `repo` scope is the only one that reads private
pull requests. Say this to the user before the device flow starts.
Both ids can be overridden without a rebuild: set `SWIM_LINEAR_CLIENT_ID` or
`SWIM_GITHUB_CLIENT_ID`. Use these to test a new registration.
