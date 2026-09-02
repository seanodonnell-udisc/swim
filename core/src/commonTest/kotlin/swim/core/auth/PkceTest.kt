package swim.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    // RFC 7636 appendix B.
    @Test
    fun challengeMatchesTheRfcVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun sha256MatchesTheKnownDigests() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
        // Longer than one 64-byte block, to exercise the message schedule across blocks.
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex(),
        )
    }

    @Test
    fun verifierIsUrlSafeUnreservedAndLongEnough() {
        val verifier = Pkce.createVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" }, "not unreserved: $verifier")
        assertNotEquals(verifier, Pkce.createVerifier())
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and 0xff
    "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0x0f]
}
