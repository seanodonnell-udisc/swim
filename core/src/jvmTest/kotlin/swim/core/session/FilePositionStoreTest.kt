package swim.core.session

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import swim.layout.Position
import swim.layout.PositionSnapshot

class FilePositionStoreTest {
    private fun tempStore(): Pair<FilePositionStore, java.nio.file.Path> {
        val dir = Files.createTempDirectory("swim-positions")
        val path = dir.resolve("positions.json")
        return FilePositionStore(path) to path
    }

    @Test
    fun aSnapshotLargerThanThePreferencesLimitRoundTrips() {
        val (store, path) = tempStore()
        val big = PositionSnapshot(
            mapOf("key" to (1..600).associate { "ENG-$it" to Position(it * 10f, it * 5f) })
        )
        store.set(big)
        assertTrue(Files.size(path) > 8192, "the fixture must exceed the prefs limit")
        assertEquals(big, store.get())
    }

    @Test
    fun aMissingFileReadsAsEmpty() {
        val (store, _) = tempStore()
        assertEquals(PositionSnapshot(), store.get())
    }

    @Test
    fun aCorruptFileReadsAsEmpty() {
        val (store, path) = tempStore()
        Files.createDirectories(path.parent)
        Files.writeString(path, "{not json")
        assertEquals(PositionSnapshot(), store.get())
    }

    @Test
    fun aFailedWriteIsSwallowedByTheSafeStore() {
        val lines = mutableListOf<String>()
        val throwing = object : PositionStore {
            override fun get(): PositionSnapshot = throw IllegalStateException("boom")
            override fun set(snapshot: PositionSnapshot) = throw IllegalArgumentException("Value too long")
        }
        val safe = SafePositionStore(throwing) { lines += it }
        safe.set(PositionSnapshot(mapOf("k" to mapOf("A-1" to Position(1f, 2f)))))
        assertEquals(PositionSnapshot(), safe.get())
        assertEquals(2, lines.size)
    }
}
