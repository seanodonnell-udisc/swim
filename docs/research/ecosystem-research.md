# Decision brief: serverless OAuth, storage, and distribution for a KMP Linear/GitHub tool

Research date: 2026-09-01. All claims cited to primary docs where possible.

**Headline:** every assumption holds, with exactly one real crack — Linear's OAuth app
manifest documents redirect URIs as *absolute HTTP or HTTPS URLs only*, so a
`linearviz://callback` custom scheme is likely rejected. Mobile needs a Universal Link /
App Link served from static hosting (GitHub Pages is enough — still no backend). Desktop
and CLI use `http://127.0.0.1:<port>` and are unaffected.

---

## 1. Linear OAuth 2.0 — PKCE public client: **supported, use it**

Linear supports PKCE, and explicitly makes `client_secret` optional at token exchange when
PKCE is used. That is the whole ballgame: you can ship a public client with no secret.

- Authorize: `https://linear.app/oauth/authorize`
- Token: `https://api.linear.app/oauth/token`
- Revoke: `https://api.linear.app/oauth/revoke`
- PKCE params: `code_challenge` + `code_challenge_method` (`S256` or `plain` — use `S256`)

Example authorize URL from Linear's own docs:

```
GET https://linear.app/oauth/authorize
  ?client_id=client1
  &redirect_uri=http://localhost:3000/oauth/callback
  &response_type=code
  &scope=read,write
  &code_challenge=<challenge>
  &code_challenge_method=S256
```

### Scopes

| Scope | Grants |
|---|---|
| `read` | Read access — issues, relations, teams, projects |
| `write` | Write access — assignee mutations |
| `issues:create` | Narrower: create issues + attachments |
| `comments:create` | Narrower: create issue comments |
| `timeSchedule:write` | Not needed |
| `admin` | Never request this |
| `app:assignable`, `app:mentionable` | Agent scopes, only for `actor=app` |

You need `read` + `write`. The narrower create-scopes are redundant once you hold `write`.

### Actor

Default is `actor=user`, which is what you want — issues you touch are attributed to the
signed-in human. `actor=app` requires workspace **admin** to install and cannot also
request `admin` scope, so avoid it.

### Redirect URIs — the caveat

The manifest schema says `oauth.redirect_uris` is an array of 1–32 unique items, each an
"absolute HTTP or HTTPS URL". HTTP is allowed, which means loopback
(`http://127.0.0.1:47821/callback`) is fine for the desktop app and the CLI. Custom
schemes are not listed as supported. I could not find a Linear doc that permits them and
could not find anyone using one.

**Plan for mobile:** host a static `https://<you>.github.io/linear-viz/oauth/callback` page
with `apple-app-site-association` and `assetlinks.json` alongside it, register that as the
redirect URI, and let Universal Links / App Links hand the code back to the app. Both
files are static; no server.

Worth a five-minute empirical check before committing: try saving a custom-scheme callback
in Settings → API → OAuth applications and see if it validates.

### Tokens

- Access tokens: valid **24 hours**
- Authorization-code flow issues **refresh tokens**, with a 30-minute grace window for
  replayed refresh requests
- Client-credentials tokens last 30 days and have no refresh — not your flow

### Rate limits

Favour OAuth over API keys, which settles the "should we just paste an API key" question:

| Auth | Requests/hr | Complexity points/hr |
|---|---|---|
| OAuth app | 5,000 per user | 2,000,000 |
| API key | 2,500 per user | 3,000,000 |
| Unauthenticated | 600 per IP | 100,000 |

Single-query complexity is capped at 10,000 points regardless of auth. All API keys
belonging to the same user share one quota. Rate-limit failures come back as **HTTP 400
with a `RATELIMITED` GraphQL error, not 429** — handle that specifically.

Useful response headers: `X-RateLimit-Requests-Limit/Remaining/Reset`,
`X-RateLimit-Complexity-Limit/Remaining/Reset`, `X-Complexity`, and endpoint-scoped
variants (`X-RateLimit-Endpoint-Requests-*`).

### Distributing the client ID in an open-source binary

A client ID is not a secret and PKCE is designed for exactly this, so shipping it is fine
and standard. Set `distribution: public` in the app manifest (default is `private`,
workspace-only) so other workspaces can authorize it. I found no evidence Linear
pre-approves or verifies apps before other workspaces can install them; review only enters
the picture if you want to be listed in Linear's integration directory.

**Naming gotcha:** `oauth.client_name` is 2–80 chars and may not contain the word "Linear"
or a URL scheme (`http://`, `https://`). So "Linear Viz" as the *registered app name* will
be rejected. Pick something else for the registration.

### Other manifest fields worth knowing

- `schemaVersion` (required): `"1.0.0"`
- `display.description` max 1000 chars; `display.iconUrl` absolute http/https, 256×256 min
- `developer.name` required, 2–80 chars
- `oauth.client_uri` required, absolute http/https homepage
- `oauth.grant_types`: `authorization_code` (always required) and/or `client_credentials`
- `webhook.url` must be **https only**, no loopback, no private-network hosts, not
  linear.app — irrelevant to you since you have no server, so omit the webhook block

Sources:
- https://linear.app/developers/oauth-2-0-authentication
- https://linear.app/developers/oauth-app-manifests
- https://linear.app/developers/rate-limiting
- https://linear.app/developers/agents

---

## 2. GitHub device flow — **confirmed, no secret needed**

Device flow works for OAuth Apps and `client_secret` is explicitly not required. You must
**enable device flow in the app's settings** first; otherwise you get `device_flow_disabled`.

- `POST https://github.com/login/device/code` → `device_code`, `user_code`,
  `verification_uri`, `expires_in`, `interval`
- `POST https://github.com/login/oauth/access_token` with
  `grant_type=urn:ietf:params:oauth:grant-type:device_code`

Codes expire after **900 seconds**. Poll no faster than `interval`; `slow_down` adds 5
seconds to your required wait.

| Error | Meaning |
|---|---|
| `authorization_pending` | User hasn't entered the code yet — keep polling |
| `slow_down` | Polled too fast — add 5s to the interval |
| `expired_token` | Device/user codes expired — request new ones |
| `access_denied` | User cancelled |
| `device_flow_disabled` | Not enabled in app settings |
| `incorrect_client_credentials`, `incorrect_device_code`, `unsupported_grant_type` | Self-explanatory |

Limit: 50 verification-code submissions per hour per application.

### Scope

To read PRs in **private org repos** you need full `repo`. There is no read-only private
scope — `public_repo` is public-only, and `(no scope)` gives public info only. That means
requesting write-capable access merely to read PRs, which is worth calling out in your
README because security-conscious users will notice. Add `read:org` if you enumerate org
membership.

### Token lifetime

OAuth App tokens do **not** expire by default and there is no refresh token unless you opt
into expiring tokens in app settings (then 8h access / 6mo refresh, matching GitHub Apps).
GitHub auto-revokes any token unused for a full year. Practical model: store it once,
handle 401 by re-running device flow.

### Two org gotchas, both real

1. **OAuth App access restrictions** are on by default for new orgs. A member authorizing
   your app gets no org data until an owner approves the request. Your error handling
   needs to say "ask an owner to approve this app," not "login failed."
2. **SAML SSO** orgs require the token to be separately authorized for the org. Same UX
   problem, different button.

Sources:
- https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps
- https://docs.github.com/en/organizations/managing-oauth-access-to-your-organizations-data/about-oauth-app-access-restrictions
- https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/token-expiration-and-revocation

---

## 3. Token storage — **write your own `expect`/`actual`, four short actuals**

There is no single library that covers all four of your targets well, and the obvious
candidate has rotted. Recommendation: a `TokenStore` interface in commonMain with four
small platform implementations, plus one trick that pays for itself.

**The trick:** have the macOS desktop app and the macOS CLI read and write the *same*
keychain generic-password item (same service name, same account). One `linear-viz login`
serves both binaries. This is the main argument for keychain over a dotfile on macOS.

| Target | Recommendation |
|---|---|
| iOS | `KeychainSettings` from multiplatform-settings 1.3.0, or `SecItemAdd`/`SecItemCopyMatching` directly via `platform.Security` |
| macOS Kotlin/Native CLI | Same — `KeychainSettings` covers all Apple targets including macOS native |
| macOS JVM desktop | **Not** multiplatform-settings — its JVM impl is `java.util.prefs`, plaintext. Shell out to `/usr/bin/security add-generic-password` / `find-generic-password -w`. ~10 lines, zero dependencies |
| Android | Neither obvious answer is clean — see below |

### Android is the messy one

`EncryptedSharedPreferences` was deprecated at `androidx.security:security-crypto:1.1.0-alpha07`
over main-thread StrictMode violations and OEM keyset-corruption crashes. Current official
guidance is DataStore + Google Tink, which is real ceremony (protobuf schema,
`CryptoManager` with `StreamingAead`, encrypted serializer, `SharedPreferencesMigration`).

For a single OAuth token the leaner path is an AES-GCM key in the Android Keystore
encrypting one string into ordinary SharedPreferences or DataStore. There is also a
maintained community fork of the AndroidX crypto library
(https://github.com/ed-george/encrypted-shared-preferences) if you'd rather keep the old API.

### Library landscape

**multiplatform-settings 1.3.0** (russhwolf) is current. Targets: Android, Apple
(iOS/macOS/watchOS/tvOS), JVM, JS, WasmJS, MinGW.

| Implementation | Backing API | Platforms |
|---|---|---|
| `SharedPreferencesSettings` | Android SharedPreferences | Android |
| `NSUserDefaultsSettings` | Apple UserDefaults | Apple |
| `KeychainSettings`* | Apple Keychain | Apple |
| `PreferencesSettings` | `java.util.prefs` (**plaintext**) | JVM |
| `PropertiesSettings` | `java.util.Properties` | JVM |
| `StorageSettings` | localStorage | JS, WasmJS |
| `RegistrySettings`* | Windows Registry | Windows |
| `DataStoreSettings`* | androidx DataStore | Android, JVM, Native |
| `MapSettings` | in-memory | all (testing) |

\* experimental

**KVault** (Liftric) is the other candidate but is mobile-only (iOS Keychain + Android
encrypted prefs) — it won't help the CLI or desktop.

**Precedent:** `gh` CLI tries the OS keyring first (macOS Keychain, wincred, Secret
Service) and only falls back to plaintext `~/.config/gh/hosts.yml` on migrated or
keyring-less setups. It exposes `--insecure-storage` as an explicit opt-out. Copy that
shape: keychain by default, documented plaintext fallback with a flag.

Sources:
- https://github.com/russhwolf/multiplatform-settings
- https://developer.android.com/jetpack/androidx/releases/security
- https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a
- https://touchlab.co/encrypted-key-value-store-kotlin-multiplatform
- https://github.com/Liftric/KVault
- https://github.com/cli/cli/issues/8954

---

## 4. GraphQL client — **plain Ktor POST + kotlinx.serialization**

Both options work on every target you care about; this is a scope call, not a capability
call.

**Apollo Kotlin 5.0.0** shipped 13 May 2026 and does support `macosArm64` — the
`com.apollographql.apollo:apollo-runtime-macosarm64` artifact is published on Maven
Central. Requires KGP 2.3 for Native and JS consumers (2.1 is enough for JVM/Android
only). Adds `linuxX64`, `linuxArm64`, `watchosDeviceArm64`, a rewritten normalized cache
with TTL/GC/pagination/binary format, and a Gradle plugin rebuilt on Gratatouille
classloader isolation. Most v4 APIs are untouched.

**But** Apollo's value is codegen plus a normalized cache, and you are issuing a handful of
hand-written queries against one schema and caching graph layouts yourself. A Ktor `POST`
to `https://api.linear.app/graphql` with a `{query, variables}` body and `@Serializable`
response classes is roughly thirty lines and adds no Gradle plugin, no schema download
step, and no build-time codegen to the CI matrix.

Engines: `ktor-client-darwin` for iOS and the macOS CLI; OkHttp or CIO for Android and JVM.
Current Ktor is **3.5.2** (31 July 2026).

Reach for Apollo later if you find yourself wanting fragment reuse across many queries or a
real normalized cache. The migration is additive.

Sources:
- https://www.apollographql.com/blog/apollo-kotlin-5-is-now-available
- https://www.apollographql.com/docs/kotlin
- https://github.com/ktorio/ktor/releases

---

## 5. Compose Multiplatform — **1.12.0, Kotlin 2.4.0, Navigation 3 in commonMain**

### Versions

- Latest stable CMP: **1.12.0**, August 2026
- Latest Kotlin: **2.4.0** (June 2026); 2.3.20 was March 2026; 2.3.0 was December 2025
- CMP 1.12 targets Kotlin language/API level 2.2, but **Kotlin 2.3+ is required for Native
  and web**, and 2.3.20 specifically for JS/Wasm

1.12.0 headline features: experimental MCP server in Compose Hot Reload (lets AI agents
trigger reloads, screenshot, inspect the semantic tree, simulate input, read logs);
on-demand Noto font subset download for web; experimental v2 `WindowState`/`DialogState`
API for finer desktop window control.

### iOS stability

**Stable and production-ready since CMP 1.8.0** (May 2025) — feature parity with Jetpack
Compose for common cases, type-safe navigation with deep links, flexible resource
management, VoiceOver / AssistiveTouch / Full Keyboard Access support. 1.11.0 added opt-in
native iOS text input (UIKit editing, native caret placement, magnifier, selection
gestures) and turned on parallel rendering by default.

### Navigation 3 — works in commonMain

Supported since **CMP 1.10.0** (January 2026). Android, iOS, desktop, and web.

```toml
[versions]
multiplatform-nav3-ui = "1.1.1"

[libraries]
jetbrains-navigation3-ui = { module = "org.jetbrains.androidx.navigation3:navigation3-ui", version.ref = "multiplatform-nav3-ui" }
# navigation3-common comes in transitively
jetbrains-material3-adaptiveNavigation3 = { module = "org.jetbrains.compose.material3.adaptive:adaptive-navigation3", version.ref = "compose-multiplatform-adaptive" }
jetbrains-lifecycle-viewmodelNavigation3 = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "compose-multiplatform-lifecycle" }
```

Nav 3 is **not** an incremental Nav 2 upgrade — you own the back stack as a
`SnapshotStateList`. Routes implement the `NavKey` marker interface and must be
`@Serializable`.

**Gotcha:** non-JVM targets (iOS, desktop-native, web) cannot use reflection-based
serialization. Build the back stack with an explicit `SerializersModule`:

```kotlin
@Serializable private data object RouteA : NavKey

private val config = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) { subclass(RouteA::class, RouteA.serializer()) }
    }
}
val backStack = rememberNavBackStack(config, RouteA)
```

Browser history support is still a proof-of-concept library (`navigation3-browser` 1.1.0);
native support tracked in CMP-8924. Doesn't affect you.

### Desktop packaging and notarization — fully supported

`compose.desktop` drives `jpackage`. **JDK 17 minimum** (JDK 18 for TestFlight).

| Task | Purpose |
|---|---|
| `packageDmg` / `packagePkg` | Build installer (macOS only) |
| `createDistributable` | App image, no installer |
| `runDistributable` | Run the packaged image |
| `notarizeDmg` | Upload for notarization |
| `checkNotarizationStatus` | Poll notarization |
| `packageReleaseDmg`, `notarizeReleaseDmg`, `checkReleaseNotarizationStatus` | ProGuard-minified variants |

Certificates: **Developer ID Application** for direct distribution (your Homebrew path).
App Store instead needs Mac App Distribution + Mac Installer Distribution and
`macOS { appStore = true }`.

```kotlin
compose.desktop.application.nativeDistributions {
    targetFormats(TargetFormat.Dmg)
    packageName = "LinearViz"
    packageVersion = "1.0.0"
    macOS {
        bundleID = "dev.example.linearviz"
        dockName = "LinearViz"
        minimumSystemVersion = "13.0"
        appCategory = "public.app-category.developer-tools"
        iconFile.set(project.file("icon.icns"))
        signing { sign.set(true); identity.set("Your Name (TEAMID)") }
        notarization { /* appleID, password, teamID — pass via env */ }
        entitlementsFile.set(file("entitlements.plist"))       // optional
        runtimeEntitlementsFile.set(file("runtime.plist"))     // optional
    }
}
```

```
./gradlew notarizeDmg \
  -Pcompose.desktop.mac.notarization.appleID=<ID> \
  -Pcompose.desktop.mac.notarization.password=<APP_SPECIFIC_PASSWORD> \
  -Pcompose.desktop.mac.notarization.teamID=<TEAM_ID>
```

Most submissions clear in under 15 minutes. `keychain` in the signing block is only needed
when you have multiple certs of the same type on the machine. The docs don't state whether
the plugin shells out to `notarytool` or the deprecated `altool` — verify on first run if
it matters.

Desktop deep links: `infoPlist { extraKeysRawXml = ... }` registers `CFBundleURLTypes`, and
`Desktop.getDesktop().setOpenURIHandler {}` receives them. Use `suggestModules` / `jdeps`
to trim the bundled JDK modules.

Sources:
- https://blog.jetbrains.com/kotlin/2026/08/compose-multiplatform-1-12-0/
- https://blog.jetbrains.com/kotlin/2026/01/compose-multiplatform-1-10-0/
- https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/
- https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html
- https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html
- https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Signing_and_notarization_on_macOS/README.md
- https://blog.jetbrains.com/kotlin/2026/06/kotlin-2-4-0-released/

---

## 6. Homebrew — **your own tap: one cask, one formula**

Do not aim for homebrew-cask. Its policy says open-source command-line software "normally
belongs in homebrew/core as a formula built from source," and a cask needs demonstrated
notability that a new project won't have (no numeric star threshold is published, but
"substantial, independently verifiable public interest and multiple requests for
inclusion" is the bar, and it "does not guarantee inclusion"). Casks must also use a
download published or publicly endorsed by the developer.

Publish `github.com/<you>/homebrew-tap` with `Casks/` and `Formula/` directories. A single
tap can hold both, plus external commands.

### Recommended structure

**`Casks/linear-viz.rb`** — the GUI app:

```ruby
cask "linear-viz" do
  version "1.0.0"
  sha256 "..."
  url "https://github.com/<you>/linear-viz/releases/download/v#{version}/LinearViz-#{version}.dmg"
  name "LinearViz"
  desc "Visualize Linear issue dependency graphs"
  homepage "https://github.com/<you>/linear-viz"

  livecheck do
    url :url
    strategy :github_latest
  end

  app "LinearViz.app"
  binary "#{appdir}/LinearViz.app/Contents/MacOS/linear-viz"

  zap trash: [
    "~/Library/Application Support/LinearViz",
    "~/Library/Preferences/dev.example.linearviz.plist",
  ]
end
```

**`Formula/linear-viz-cli.rb`** — binary-only formula for headless/CI use:

```ruby
class LinearVizCli < Formula
  desc "CLI for Linear issue dependency graphs"
  homepage "https://github.com/<you>/linear-viz"
  url "https://github.com/<you>/linear-viz/releases/download/v1.0.0/linear-viz-cli-1.0.0-macos-arm64.tar.gz"
  sha256 "..."
  license "MIT"

  def install
    bin.install "linear-viz"
    pkgshare.install "agent.md", "skill.md"
    doc.install "README.md"
  end

  test do
    assert_match "linear-viz", shell_output("#{bin}/linear-viz --version")
  end
end
```

Support files land in `#{HOMEBREW_PREFIX}/share/linear-viz/` via `pkgshare`, which is the
conventional home for them. `doc`, `man1`–`man8`, `libexec`, `share`, `prefix` are the
other install helpers. Homebrew docs advise installing specific files rather than
`prefix.install Dir["*"]`.

**Why both:** the `binary` stanza inside the cask is neat — one install gives GUI and CLI,
versions can never drift. The downside is CLI-only users are forced to install a GUI app.
Given the CLI is the interesting artifact for agent workflows, ship both.

**Do not** try `depends_on cask:` from a formula — it errors with "Unsupported special
dependency :cask" and is explicitly unsupported even in third-party taps.

### Two things that will bite you

1. **Tap Trust**, new in Homebrew 6.0.0 (June 2026). Installing by short name from a
   third-party tap now fails *silently* until the user runs `brew trust <user>/<repo>`.
   There is no interactive prompt and no way for a maintainer to pre-authorize. But
   **fully qualified names skip the trust check entirely** — so your README must say:

   ```
   brew install <you>/tap/linear-viz-cli
   brew install --cask <you>/tap/linear-viz
   ```

   Never the short form.

2. **Gatekeeper.** Casks must pass Homebrew's Gatekeeper checks and must not require SIP or
   Gatekeeper to be disabled. Notarization (§5) is mandatory, not optional.

### Updates

`brew upgrade` only, unless you bolt on Sparkle. Do not do both — set `auto_updates true`
if the app self-updates (that tells Homebrew to stay out of the way), otherwise leave it
off and add a `livecheck` block pointing at GitHub releases so Homebrew's autobump can see
new versions. `livecheck` is not used with `version :latest` unless the block uses `skip`.

Sources:
- https://docs.brew.sh/Cask-Cookbook
- https://docs.brew.sh/Acceptable-Casks
- https://docs.brew.sh/Formula-Cookbook
- https://docs.brew.sh/Taps
- https://docs.brew.sh/Tap-Trust
- https://brew.sh/2026/06/11/homebrew-6.0.0/
- https://github.com/orgs/Homebrew/discussions/5788

---

## 7. klibs.io — **four requirements, all automatic**

You do not submit anything. A project is indexed within about a month if it meets **all
four**:

1. Open source and hosted on GitHub.
2. At least one artifact published to **Maven Central**.
3. At least one artifact is multiplatform — must contain `kotlin-tooling-metadata.json`.
4. At least one artifact's POM contains a **valid link to the GitHub repository**.

New versions of an already-indexed project appear the day after they hit Maven Central.
Ranking considers query relevance, popularity, and project activity.

klibs.io generates supplementary metadata with an LLM when source metadata is thin (and
states library content is not used for training or fine-tuning), so **a clear POM
`description` and a good README are the levers you have** — write them for someone
searching "Linear GraphQL Kotlin Multiplatform," not for someone who already knows the
project. The catalog passed 4,200 projects in August 2026 and now has AI/MCP integrations
for discovery.

**Caveat:** this only matters if you publish the shared core as a **library** to Maven
Central. An app-only repo will never appear.

Sources:
- https://klibs.io/faq
- https://blog.jetbrains.com/kotlin/2024/12/introducing-klibs-io-a-new-way-to-discover-kotlin-multiplatform-libraries/
- https://blog.jetbrains.com/kotlin/2026/08/klibsio-grows-to-4200-kmp-projects-with-smarter-discovery-and-new-ai-integrations/

---

## 8. App Store / Play Store — **you are explicitly exempted from Sign in with Apple**

Best news in the brief. Guideline **4.8** requires an Apple-equivalent login option when a
third-party service authenticates the user's *primary account* — but it lists an exception
that describes your app almost word for word:

> Another login service is not required if: … Your app is a client for a specific
> third-party service and users are required to sign in to their mail, social media, or
> other third-party account directly to access their content.

A Linear client where the user signs into their own Linear workspace is that. **No Sign in
with Apple needed.**

**Guideline 5.1.1(v)** says apps without significant account-based features must work
without a login — yours has nothing to show without a workspace, so requiring login is
defensible. Two clauses that do apply:

- If you support account *creation* you must offer in-app deletion. You don't create
  accounts, so moot.
- You must let the user **revoke credentials and disable data access from within the app**
  — ship a visible "Sign out / disconnect Linear / disconnect GitHub" that actually calls
  `https://api.linear.app/oauth/revoke`.
- The guideline also forbids storing tokens off-device, which your architecture already
  satisfies.

**Guideline 2.1(a)** is the one that will actually get you rejected:

> include demo account info (and turn on your back-end service!) if your app includes a
> login. If you are unable to provide a demo account due to legal or security obligations,
> you may include a built-in demo mode in lieu of a demo account **with prior approval by
> Apple**. Ensure the demo mode exhibits your app's full features and functionality.

So: set up a throwaway Linear workspace with representative issues and dependencies, and
put those credentials in App Store Connect review notes. A built-in demo mode is permitted
only with prior Apple approval, so don't rely on it as plan A. Build one anyway for
screenshots and the App Store preview, but treat the demo workspace as the primary answer.

**Play Store** has no Sign-in-with-Apple equivalent, but requires the same thing in
practice: working demo credentials in the "App access" section of the Play Console, or
reviewers bounce the release.

Sources:
- https://developer.apple.com/app-store/review/guidelines/ (4.8, 5.1.1(v), 2.1)

---

## Assumption check

| Assumption | Verdict |
|---|---|
| Serverless OAuth viable for both providers | **Holds.** Linear PKCE public client, GitHub device flow. One caveat: Linear redirect URIs are documented http/https-only, so mobile needs a Universal Link / App Link from static hosting rather than a custom scheme. |
| Kotlin/Native CLI can share the Ktor-based core | **Holds.** `ktor-client-darwin` on `macosArm64`; multiplatform-settings `KeychainSettings` covers the same target for token storage. |
| CMP desktop can be notarized for Homebrew | **Holds.** `notarizeDmg` with a Developer ID Application certificate; Homebrew's Gatekeeper check makes it mandatory, not optional. |

Two risks not in the original assumptions:

1. Reading PRs in private org repos requires GitHub's full **`repo`** scope — no read-only
   alternative exists. Document this prominently.
2. **Tap Trust** in Homebrew 6.0 silently breaks short-name installs from third-party taps.
   Install instructions must be fully qualified.

One naming constraint: Linear's registered `client_name` may not contain the word "Linear".
