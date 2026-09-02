package swim.core.auth

import java.security.SecureRandom

private val secureRandom by lazy { SecureRandom() }

internal actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }
