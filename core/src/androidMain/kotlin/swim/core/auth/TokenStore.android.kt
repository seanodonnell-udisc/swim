package swim.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import swim.core.androidContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun createTokenStore(): TokenStore = AndroidTokenStore(androidContext())

/**
 * An AES-GCM key in the Android keystore encrypts one string for each provider. The result
 * goes into private shared preferences. EncryptedSharedPreferences is deprecated, and Tink
 * needs much more code than two strings justify.
 */
class AndroidTokenStore(context: Context) : KeyValueTokenStore() {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val payload = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(payload).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    override fun write(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.doFinal(value.encodeToByteArray())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(payload, Base64.NO_WRAP)
        preferences.edit().putString(key, encoded).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}

private const val PREFERENCES = "swim.tokens"
private const val PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "swim.tokens"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val SEPARATOR = ":"
private const val TAG_BITS = 128
