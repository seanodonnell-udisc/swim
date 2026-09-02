package swim.core.config

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwimConfigJvmTest {
    private fun tempConfig(): String =
        File(Files.createTempDirectory("swim-config").toFile(), "nested/config.json").path

    @Test
    fun configSurvivesARoundTrip() {
        val path = tempConfig()
        val config = SwimConfig(repos = listOf("/code/app", "/code/api"), showVersionLabels = true)

        saveConfig(config, path)

        assertEquals(config, loadConfig(path))
    }

    @Test
    fun theFileIsPrettyPrintedAndReadableByItsOwnerOnly() {
        val path = tempConfig()
        saveConfig(SwimConfig(repos = listOf("/code/app")), path)

        val text = File(path).readText()
        assertTrue(text.contains("\n"), "the config is pretty-printed")
        assertTrue(text.endsWith("\n"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(File(path).toPath()),
        )
    }

    @Test
    fun theDirectoryHoldingTheTokensIsClosedToOtherUsers() {
        val path = tempConfig()
        saveConfig(SwimConfig(), path)

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(File(path).parentFile.toPath()),
        )
    }

    @Test
    fun aRewriteRestoresTheOwnerOnlyMode() {
        val path = tempConfig()
        saveConfig(SwimConfig(), path)
        Files.setPosixFilePermissions(
            File(path).toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OTHERS_READ),
        )

        saveConfig(SwimConfig(repos = listOf("/code/app")), path)

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(File(path).toPath()),
        )
    }

    @Test
    fun aMissingOrCorruptFileYieldsTheDefaults() {
        val path = tempConfig()
        assertEquals(SwimConfig(), loadConfig(path))

        File(path).parentFile.mkdirs()
        File(path).writeText("{ this is not json")
        assertEquals(SwimConfig(), loadConfig(path))
    }

    @Test
    fun theConfigDirectoryIsPerOperatingSystem() {
        val directory = configDir()
        assertTrue(directory.endsWith("/swim"), directory)
        assertTrue(
            directory.contains("Library/Application Support") || directory.contains("config"),
            directory,
        )
    }
}
