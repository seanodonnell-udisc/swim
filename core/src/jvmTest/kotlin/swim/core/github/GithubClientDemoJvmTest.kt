package swim.core.github

import kotlinx.coroutines.test.runTest
import swim.core.Canned
import swim.core.HttpRecorder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Demo mode reads a real file, so these run on the JVM where java.io is available.
class GithubClientDemoJvmTest {
    private fun demoFile(text: String): String {
        val path = File(Files.createTempDirectory("swim-demo-prs").toFile(), "demo.json").path
        File(path).writeText(text)
        return path
    }

    @Test
    fun aDemoFileServesStatusesAndMakesNoHttpCall() = runTest {
        val path = demoFile(
            """{"https://github.com/acme/app/pull/7":
                {"headRefName":"feat/x","baseRefName":"main","reviewDecision":"APPROVED","checkState":"SUCCESS"}}""",
        )
        val recorder = HttpRecorder(Canned("""should never be read"""))
        val lines = mutableListOf<String>()
        val github = GithubClient(recorder.client, { lines += it }, demoPath = path) { "gho_token" }

        val statuses = github.getPrStatuses(listOf("https://github.com/acme/app/pull/7"))!!

        assertEquals(0, recorder.requests.size)
        assertEquals("APPROVED", statuses["https://github.com/acme/app/pull/7"]?.reviewDecision)
        assertEquals("SUCCESS", statuses["https://github.com/acme/app/pull/7"]?.checkState)
        assertEquals(1, lines.size)
        assertContains(lines[0], path)
        assertContains(lines[0], "1 entries")
    }

    @Test
    fun aUrlAbsentFromTheDemoFileIsSimplyAbsent() = runTest {
        val path = demoFile(
            """{"https://github.com/acme/app/pull/7": {"reviewDecision":"APPROVED"}}""",
        )
        val github = GithubClient(HttpRecorder(Canned("{}")).client, demoPath = path) { "gho_token" }

        val statuses = github.getPrStatuses(
            listOf("https://github.com/acme/app/pull/7", "https://github.com/acme/app/pull/99"),
        )!!

        assertEquals(1, statuses.size)
        assertTrue("https://github.com/acme/app/pull/99" !in statuses)
    }

    @Test
    fun aMalformedDemoFileFallsBackToTheNetwork() = runTest {
        val path = demoFile("{ not json")
        val recorder = HttpRecorder(Canned("""{"data":{}}"""))
        val lines = mutableListOf<String>()
        val github = GithubClient(recorder.client, { lines += it }, demoPath = path) { "gho_token" }

        github.getPrStatuses(listOf("https://github.com/acme/app/pull/7"))

        assertEquals(1, recorder.requests.size, "a malformed demo file must fall back to GitHub")
        assertContains(lines[0], "malformed")
    }

    @Test
    fun aMissingDemoFileFallsBackToTheNetworkWithoutCrashing() = runTest {
        val path = File(Files.createTempDirectory("swim-demo-prs").toFile(), "missing.json").path
        val recorder = HttpRecorder(Canned("""{"data":{}}"""))
        val lines = mutableListOf<String>()
        val github = GithubClient(recorder.client, { lines += it }, demoPath = path) { "gho_token" }

        github.getPrStatuses(listOf("https://github.com/acme/app/pull/7"))

        assertEquals(1, recorder.requests.size)
        assertContains(lines[0], "not found")
    }

    @Test
    fun withDemoUnsetBehaviorIsUnchanged() = runTest {
        val recorder = HttpRecorder(Canned("""{"data":{}}"""))
        val github = GithubClient(recorder.client, demoPath = null) { "gho_token" }

        github.getPrStatuses(listOf("https://github.com/acme/app/pull/7"))

        assertEquals(1, recorder.requests.size, "no demo path means the real client runs as before")
    }

    @Test
    fun demoConfiguredIsTrueOnlyForAParsingFile() {
        val good = demoFile("""{"https://github.com/acme/app/pull/7": {}}""")
        val bad = demoFile("{ not json")

        assertTrue(demoPrsConfigured(good))
        assertTrue(!demoPrsConfigured(bad))
        assertTrue(!demoPrsConfigured(null))
    }
}
