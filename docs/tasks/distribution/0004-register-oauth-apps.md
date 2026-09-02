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

## One-click registration
Open this URL while signed in to Linear. It pre-fills the form from a manifest. Review and create. Copy the client id:

https://linear.app/settings/api/applications/new?manifest=%7B%22schemaVersion%22%3A%20%221.0.0%22%2C%20%22developer%22%3A%20%7B%22name%22%3A%20%22Sean%20O%27Donnell%22%7D%2C%20%22display%22%3A%20%7B%22description%22%3A%20%22Swim%20visualizes%20issue%20dependency%20graphs%20and%20gives%20agents%20a%20planning%20CLI.%22%7D%2C%20%22oauth%22%3A%20%7B%22client_name%22%3A%20%22Swim%22%2C%20%22client_uri%22%3A%20%22https%3A//github.com/seanodonnell-udisc/swim%22%2C%20%22redirect_uris%22%3A%20%5B%22http%3A//127.0.0.1%3A8976/callback%22%2C%20%22http%3A//localhost%3A8976/callback%22%2C%20%22https%3A//seanodonnell-udisc.github.io/swim/oauth/callback%22%5D%2C%20%22grant_types%22%3A%20%5B%22authorization_code%22%5D%2C%20%22distribution%22%3A%20%22public%22%7D%7D

For GitHub, open https://github.com/settings/applications/new. Set name "Swim", the repo URL as homepage, and `http://127.0.0.1:8976/callback` as the callback. After creation, turn ON "Device flow" in the app settings. Copy the client id.

## Notes
Linear rejects an app name that contains "Linear".
The loopback port is FIXED at 8976. The CLI and the desktop app both bind it, and the
registered redirect URI names it. See `OAuthApps.LOOPBACK_PORT`.
GitHub gives no read-only private scope. The `repo` scope is the only one that reads private
pull requests. Say this to the user before the device flow starts.
Both ids can be overridden without a rebuild: set `SWIM_LINEAR_CLIENT_ID` or
`SWIM_GITHUB_CLIENT_ID`. Use these to test a new registration.
