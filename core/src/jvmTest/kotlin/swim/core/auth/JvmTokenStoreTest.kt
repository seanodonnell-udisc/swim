@file:OptIn(ExperimentalTime::class)

package swim.core.auth

import swim.core.model.AuthError
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

// The real keychain is never touched here: useKeychain = false forces the documented file path.
class JvmTokenStoreTest {
    private fun store(path: String) = JvmTokenStore(filePath = path, useKeychain = false)

    private fun tempTokens(): String =
        File(Files.createTempDirectory("swim-tokens").toFile(), "tokens.json").path

    @Test
    fun linearTokensSurviveTheFileFallback() {
        val path = tempTokens()
        val tokens = LinearTokens(
            accessToken = "lin_at",
            refreshToken = "lin_rt",
            expiresAt = Clock.System.now() + 24.hours,
        )

        store(path).setLinear(tokens)

        assertEquals(tokens, store(path).getLinear())
        assertTrue(File(path).readText().contains("swim.linear"))
    }

    @Test
    fun theGithubTokenSurvivesTheFileFallback() {
        val path = tempTokens()
        store(path).setGithub("gho_token")
        assertEquals("gho_token", store(path).getGithub())
    }

    @Test
    fun clearingRemovesOnlyThatProvider() {
        val path = tempTokens()
        val store = store(path)
        store.setLinear(LinearTokens("lin_at", "lin_rt", Clock.System.now() + 24.hours))
        store.setGithub("gho_token")

        store.clearLinear()

        assertNull(store.getLinear())
        assertEquals("gho_token", store.getGithub())
    }

    @Test
    fun anAbsentOrUnreadableFileReadsAsNoTokens() {
        val path = tempTokens()
        assertNull(store(path).getLinear())
        assertNull(store(path).getGithub())

        File(path).writeText("not json")
        assertNull(store(path).getLinear())
    }

    @Test
    fun theSecretReachesSecurityOnStdinAndNeverInArgv() {
        val secret = "lin_oauth_super_secret"

        assertEquals(listOf("/usr/bin/security", "-i"), SECURITY_STDIN_ARGV)
        assertTrue(SECURITY_STDIN_ARGV.none { it.contains(secret) })
        assertTrue(keychainWriteCommand(LINEAR_KEY, secret).contains(secret), "stdin carries it")
    }

    @Test
    fun theKeychainCommandQuotesEveryArgumentTheParserWouldSplit() {
        // `security -i` splits on spaces and drops a backslash before any character.
        assertEquals(
            """add-generic-password -U -s "swim" -a "swim.linear" -w "{\"a\":\"b c\\\\d\"}"""",
            keychainWriteCommand(LINEAR_KEY, """{"a":"b c\\d"}"""),
        )
    }

    @Test
    fun aKeychainThatRefusesTheWriteDoesNotPutTheSecretOnDisk() {
        val path = tempTokens()
        val store = JvmTokenStore(filePath = path, useKeychain = true, keychainWrite = { false })

        assertFailsWith<AuthError> { store.setLinear(LinearTokens("lin_at", "lin_rt")) }

        assertTrue(!File(path).exists(), "a locked keychain must not divert the token to a file")
    }

    @Test
    fun anExpiredAccessTokenSaysSo() {
        val expired = LinearTokens("lin_at", "lin_rt", Clock.System.now() - 1.hours)
        val fresh = LinearTokens("lin_at", "lin_rt", Clock.System.now() + 24.hours)
        assertTrue(expired.isExpired())
        assertTrue(!fresh.isExpired())
    }
}
