@file:OptIn(ExperimentalTime::class)

package swim.core.auth

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun anExpiredAccessTokenSaysSo() {
        val expired = LinearTokens("lin_at", "lin_rt", Clock.System.now() - 1.hours)
        val fresh = LinearTokens("lin_at", "lin_rt", Clock.System.now() + 24.hours)
        assertTrue(expired.isExpired())
        assertTrue(!fresh.isExpired())
    }
}
