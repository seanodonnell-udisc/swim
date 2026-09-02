package swim.core.auth

import io.ktor.http.decodeURLQueryComponent

/**
 * The page the browser shows after the OAuth redirect. The loopback servers on desktop and the
 * CLI both serve exactly this.
 */
internal const val CALLBACK_HTML: String =
    "<!doctype html><meta charset=\"utf-8\"><title>Swim</title>" +
        "<body style=\"font:16px system-ui;padding:3rem\">You can close this window.</body>"

/** Splits a raw `a=1&b=2` query into decoded pairs. */
internal fun parseCallbackQuery(raw: String?): Map<String, String> =
    raw.orEmpty()
        .split("&")
        .filter { it.isNotEmpty() }
        .mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                pair.substring(0, separator).decodeURLQueryComponent() to
                    pair.substring(separator + 1).decodeURLQueryComponent(plusIsSpace = true)
            }
        }
        .toMap()
