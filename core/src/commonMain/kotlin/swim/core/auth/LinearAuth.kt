package swim.core.auth

import swim.core.linear.AuthHeaderProvider
import swim.core.linear.apiKeyAuth
import swim.core.linear.oauthAuth
import swim.core.model.AuthError

/** Turns the stored Linear credential into the header form that credential needs. */
object LinearAuth {
    /**
     * The header provider for whatever is in the store, or null when nobody has signed in.
     * An OAuth access token that has expired is refreshed once, stored, and then used.
     */
    fun provider(store: TokenStore, oauth: LinearOAuth? = null): AuthHeaderProvider? {
        val stored = store.getLinear() ?: return null
        if (stored.mode == LinearAuthMode.API_KEY) return apiKeyAuth(stored.accessToken)

        return oauthAuth {
            val current = store.getLinear() ?: throw AuthError(SIGNED_OUT)
            if (!current.isExpired()) {
                current.accessToken
            } else {
                val refreshToken = current.refreshToken ?: throw AuthError(EXPIRED)
                val client = oauth ?: throw AuthError(EXPIRED)
                val renewed = client.refresh(refreshToken).copy(mode = LinearAuthMode.OAUTH)
                store.setLinear(renewed)
                renewed.accessToken
            }
        }
    }
}

private const val SIGNED_OUT = "No Linear credentials. Run `swim auth`."
private const val EXPIRED = "The Linear session expired. Run `swim auth`."
