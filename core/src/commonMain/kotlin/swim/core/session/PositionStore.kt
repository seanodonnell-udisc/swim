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

/**
 * Marks one saved arrangement as touched by hand.
 *
 * It is a reserved entry inside that arrangement's own position map, the way `@group:` holds an
 * area's drag offset and `@stack:` names a pile. A Linear identifier is `TEAM-123`, so it can
 * never start with `@` and can never collide with a real node. Riding in the map is what makes it
 * free to persist: it is written, read and thrown away with the layout it describes, and no
 * second store has to be kept in step with it.
 *
 * What it decides: collision-avoiding connector routing is the machine's guess at a tidy picture,
 * and it is only welcome until the user arranges the graph themselves. Once a card, a pile or an
 * area has been moved under a key, that key draws direct connectors for good — what the user saw
 * while dragging is what stays, on this launch and every one after. A re-layout throws the whole
 * saved arrangement away, this mark with it, so the routes come back.
 *
 * Every consumer strips it before the layout or the cache sees the map: it is not a node and
 * [HAND_EDITED], its value, means nothing.
 */
const val EDITED_KEY: String = "@edited"

/** The value stored against [EDITED_KEY]. Only the presence of the key carries meaning. */
val HAND_EDITED: Position = Position(0f, 0f)

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
