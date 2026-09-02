package swim.core.session

import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import swim.core.model.FilterOptions
import swim.layout.Position
import swim.layout.PositionSnapshot

/** Where hand-placed node positions live between launches. */
interface PositionStore {
    /** Every saved layout. An unreadable store reads as empty. */
    fun get(): PositionSnapshot

    /** Replaces every saved layout. */
    fun set(snapshot: PositionSnapshot)
}

/** The settings key node positions persist under. */
const val POSITIONS_KEY: String = "swim.positions"

/** A [PositionStore] over one JSON string in platform settings. */
class SettingsPositionStore(
    private val settings: Settings,
    private val key: String = POSITIONS_KEY,
) : PositionStore {
    override fun get(): PositionSnapshot = decodeSnapshot(settings.getStringOrNull(key))

    override fun set(snapshot: PositionSnapshot) {
        settings.putString(key, encodeSnapshot(snapshot))
    }
}

/**
 * A [PositionStore] that logs a failed write instead of throwing. Positions are a convenience;
 * losing one save must never take the app down.
 */
class SafePositionStore(
    private val delegate: PositionStore,
    private val log: (String) -> Unit = {},
) : PositionStore {
    override fun get(): PositionSnapshot = try {
        delegate.get()
    } catch (e: Exception) {
        log("position read failed: ${e.message}")
        PositionSnapshot()
    }

    override fun set(snapshot: PositionSnapshot) {
        try {
            delegate.set(snapshot)
        } catch (e: Exception) {
            log("position save failed: ${e.message}")
        }
    }
}

internal fun decodeSnapshot(stored: String?): PositionSnapshot {
    if (stored == null) return PositionSnapshot()
    return try {
        PositionSnapshot(
            positionJson.decodeFromString(SNAPSHOT, stored)
                .mapValues { (_, byId) -> byId.mapValues { (_, p) -> Position(p.x, p.y) } }
        )
    } catch (e: Exception) {
        PositionSnapshot()
    }
}

internal fun encodeSnapshot(snapshot: PositionSnapshot): String {
    val stored = snapshot.byKey.mapValues { (_, byId) ->
        byId.mapValues { (_, p) -> StoredPosition(p.x, p.y) }
    }
    return positionJson.encodeToString(SNAPSHOT, stored)
}

/**
 * The key one query's layout is saved under. The text is canonical JSON: the field order is the
 * declaration order of [FilterOptions], so REORDERING OR RENAMING A FILTER FIELD INVALIDATES
 * every saved layout. Add new fields at the end and give them a default.
 */
fun cacheKey(filters: FilterOptions, groupBy: GraphGrouping): String =
    positionJson.encodeToString(CacheKeyInput.serializer(), CacheKeyInput(filters, groupBy))

/**
 * The grouping a [cacheKey] was built with, or null when the key did not come from [cacheKey].
 * Layout inheritance uses this: a grouped arrangement means nothing to a flat view.
 */
fun groupingOf(key: String): GraphGrouping? = runCatching {
    positionJson.decodeFromString(CacheKeyInput.serializer(), key).groupBy
}.getOrNull()

@Serializable
private data class CacheKeyInput(val filters: FilterOptions, val groupBy: GraphGrouping)

@Serializable
private data class StoredPosition(val x: Float, val y: Float)

private val SNAPSHOT = MapSerializer(
    String.serializer(),
    MapSerializer(String.serializer(), StoredPosition.serializer()),
)

private val positionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
