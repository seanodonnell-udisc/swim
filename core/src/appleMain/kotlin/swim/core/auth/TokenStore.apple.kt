@file:OptIn(ExperimentalSettingsImplementation::class)

package swim.core.auth

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings

actual fun createTokenStore(): TokenStore = KeychainTokenStore()

/**
 * Apple keychain credentials, for iOS and for the macOS CLI. KeychainSettings writes a generic
 * password whose service is the constructor argument and whose account is the key, so the item
 * is exactly the one the macOS JVM app addresses through `/usr/bin/security`.
 */
class KeychainTokenStore(
    private val settings: KeychainSettings = KeychainSettings(KEYCHAIN_SERVICE),
) : KeyValueTokenStore() {
    override fun read(key: String): String? = settings.getStringOrNull(key)
    override fun write(key: String, value: String) = settings.putString(key, value)
    override fun remove(key: String) = settings.remove(key)
}
