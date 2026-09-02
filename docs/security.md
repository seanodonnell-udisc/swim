# Security

This page shows how Swim keeps your Linear and GitHub credentials safe.

## Where the tokens live

| Platform | Store |
|---|---|
| iOS, and the macOS CLI | Apple keychain, through `KeychainSettings` |
| macOS desktop app | The same keychain item, through `/usr/bin/security` |
| Android | An AES-GCM key in the Android keystore, over private preferences |
| Other desktop systems | A 0600 JSON file in the config directory |

The keychain item is one generic password. The service is `swim`. The account is `swim.linear`
or `swim.github`. The CLI and the macOS app address the same item. One sign-in serves both.

macOS gives each binary its own access to a keychain item. The first read from a new binary
shows an authorization prompt. Task #0005 removes the prompt with a Security.framework binding.

## The file fallback

Start the desktop app with `-Dswim.insecureStorage=true` to keep the tokens in a file. The
store prints one warning line each run while the file is in use. The file mode is 0600. The
directory mode is 0700. Swim sets both modes again on every write.

A keychain that refuses a write is an error. Swim never moves the secret to a file for you.

## OAuth

- Linear uses the authorization code flow with PKCE. The challenge method is S256.
- Each authorize request carries a random `state`. The callback must return the same value.
- The callback server binds 127.0.0.1 only. The port is 8976.
- Each endpoint is a compiled-in https constant. No setting changes it to http.
- Sign-out calls the Linear revoke endpoint. It then clears both tokens from every store.
- GitHub uses the device flow. Swim shows the user code. Swim never shows the device code.

## What Swim never writes

Swim writes no secret to a log line, an error message or a `--json` payload. This rule covers
the access token, the API key, the refresh token, the device code and the PKCE verifier. Swim
sends these values only in the `Authorization` header or in a request body.

Do not put a secret on the command line. `swim auth --key -` reads the key from stdin. Swim
stores a keychain secret with `security -i`, which reads the command from stdin. No secret
reaches the argument list, where every local user can read it.

## Accepted limits

- The JVM cannot erase a `String`. Swim does not clear a token from memory.
- `KeychainSettings` adds its properties to reads as well as writes. An explicit accessibility
  class would break reads of items that another binary wrote. Swim therefore keeps the Apple
  default, which is `kSecAttrAccessibleWhenUnlocked`.

## Threat model

Swim defends against these attacks:

- Another user of this machine reads your credential file.
- Another user lists the processes and reads their arguments.
- A secret leaves Swim in a log file, a terminal or a JSON payload.

Swim does not defend against these attacks:

- An attacker gets root access, or gets your unlocked account.
- An attacker reads the memory of a running process.
- An attacker controls your browser.
