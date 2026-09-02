package swim.core.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** RFC 7636 PKCE, S256 only. */
object Pkce {
    /** A 43-character code verifier. Base64url output is a subset of the unreserved set RFC 7636 allows. */
    fun createVerifier(): String = base64Url(secureRandomBytes(32))

    /** An opaque `state` value for the authorize request. */
    fun createState(): String = base64Url(secureRandomBytes(16))

    /** The S256 code challenge for `verifier`. */
    fun challenge(verifier: String): String = base64Url(sha256(verifier.encodeToByteArray()))
}

/** Cryptographically secure random bytes. */
internal expect fun secureRandomBytes(size: Int): ByteArray

@OptIn(ExperimentalEncodingApi::class)
private val base64UrlNoPad = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@OptIn(ExperimentalEncodingApi::class)
internal fun base64Url(bytes: ByteArray): String = base64UrlNoPad.encode(bytes)

// SHA-256 round constants (FIPS 180-4). Parsed from hex to keep 64 literals readable.
private val K: IntArray = (
    "428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5 " +
        "d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174 " +
        "e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da " +
        "983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967 " +
        "27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85 " +
        "a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070 " +
        "19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3 " +
        "748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2"
    ).split(" ").map { it.toUInt(16).toInt() }.toIntArray()

/** SHA-256. No common-stdlib digest exists, and one hash is not worth a dependency or four actuals. */
internal fun sha256(input: ByteArray): ByteArray {
    val h = intArrayOf(
        0x6a09e667, 0xbb67ae85u.toInt(), 0x3c6ef372, 0xa54ff53au.toInt(),
        0x510e527f, 0x9b05688cu.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )

    val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    val bitLength = input.size.toLong() * 8
    for (i in 0 until 8) {
        padded[padded.size - 1 - i] = ((bitLength ushr (8 * i)) and 0xff).toByte()
    }

    val w = IntArray(64)
    var offset = 0
    while (offset < padded.size) {
        for (i in 0 until 16) {
            val p = offset + i * 4
            w[i] = ((padded[p].toInt() and 0xff) shl 24) or
                ((padded[p + 1].toInt() and 0xff) shl 16) or
                ((padded[p + 2].toInt() and 0xff) shl 8) or
                (padded[p + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val x = w[i - 15]
            val y = w[i - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var acc = h[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = acc + s1 + ch + K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            acc = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += acc
        offset += 64
    }

    val out = ByteArray(32)
    for (i in 0 until 8) {
        for (j in 0 until 4) out[i * 4 + j] = ((h[i] ushr (24 - 8 * j)) and 0xff).toByte()
    }
    return out
}
