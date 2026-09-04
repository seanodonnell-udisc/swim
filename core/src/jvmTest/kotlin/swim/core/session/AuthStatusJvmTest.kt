package swim.core.session

import swim.core.auth.LinearAuthMode
import swim.core.auth.LinearTokens
import swim.core.auth.TokenStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Demo mode reads a real file, so this runs on the JVM where java.io is available.
class AuthStatusJvmTest {
    private class FakeTokenStore(
        private val linear: LinearTokens? = null,
        private val github: String? = null,
    ) : TokenStore {
        override fun getLinear(): LinearTokens? = linear
        override fun setLinear(tokens: LinearTokens) = error("not used")
        override fun clearLinear() = error("not used")
        override fun getGithub(): String? = github
        override fun setGithub(token: String) = error("not used")
        override fun clearGithub() = error("not used")
    }

    private fun demoFile(): String {
        val path = File(Files.createTempDirectory("swim-demo-prs").toFile(), "demo.json").path
        File(path).writeText("""{"https://github.com/acme/app/pull/7": {}}""")
        return path
    }

    @Test
    fun demoModeReportsGithubConfiguredWithNoToken() {
        val store = FakeTokenStore(linear = LinearTokens("lin_at", mode = LinearAuthMode.API_KEY))

        val status = authStatus(store, demoPrsPath = demoFile())

        assertTrue(status.githubConfigured)
    }

    @Test
    fun withNoTokenAndNoDemoFileGithubIsNotConfigured() {
        val store = FakeTokenStore(linear = LinearTokens("lin_at", mode = LinearAuthMode.API_KEY))

        val status = authStatus(store, demoPrsPath = null)

        assertFalse(status.githubConfigured)
    }

    @Test
    fun aRealGithubTokenStillReportsConfiguredWithNoDemoFile() {
        val store = FakeTokenStore(
            linear = LinearTokens("lin_at", mode = LinearAuthMode.API_KEY),
            github = "gho_token",
        )

        val status = authStatus(store, demoPrsPath = null)

        assertTrue(status.githubConfigured)
    }
}
