package swim.core.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import swim.layout.PositionSnapshot

/**
 * A [PositionStore] over one JSON file. `java.util.prefs` caps a value at 8 KB, which a real
 * workspace's snapshot exceeds, so the desktop app stores positions here instead.
 */
class FilePositionStore(private val path: Path) : PositionStore {
    override fun get(): PositionSnapshot = decodeSnapshot(
        if (Files.exists(path)) Files.readString(path) else null
    )

    override fun set(snapshot: PositionSnapshot) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, encodeSnapshot(snapshot))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
