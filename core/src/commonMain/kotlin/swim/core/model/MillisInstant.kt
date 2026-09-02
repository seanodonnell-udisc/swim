@file:OptIn(ExperimentalTime::class)

package swim.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * ISO-8601 UTC with exactly three fraction digits. The default format drops a zero fraction, so
 * the same moment prints two ways. Agents compare these strings, so the width has to be fixed.
 */
object MillisInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("swim.core.model.MillisInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        val body = value.toString().removeSuffix("Z")
        val dot = body.indexOf('.')
        val seconds = if (dot < 0) body else body.substring(0, dot)
        val fraction = if (dot < 0) "" else body.substring(dot + 1)
        encoder.encodeString("$seconds.${fraction.padEnd(3, '0').take(3)}Z")
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
