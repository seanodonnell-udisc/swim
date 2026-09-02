package swim.core.model

import kotlin.time.Duration

/** Every failure :core reports. Callers map these to CLI exit codes and UI messages. */
sealed class SwimError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A filter names a team or project that does not exist, or names nothing at all. */
class ScopeError(message: String) : SwimError(message)

/** A named issue does not exist. */
class NotFoundError(message: String) : SwimError(message)

/** The token is missing, rejected, or lacks the scope the operation needs. */
class AuthError(message: String) : SwimError(message)

/** The provider refused the request for rate reasons and one retry did not clear it. */
class RateLimitedError(message: String, val retryAfter: Duration? = null) : SwimError(message)

/** The request did not complete: no route to host, TLS failure, timeout. */
class NetworkError(message: String, cause: Throwable? = null) : SwimError(message, cause)

/** The provider answered, and the answer was an error. */
class ApiError(message: String) : SwimError(message)
