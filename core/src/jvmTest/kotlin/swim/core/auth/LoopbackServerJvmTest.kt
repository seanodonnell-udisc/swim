package swim.core.auth

import kotlin.test.Test
import kotlin.test.assertTrue

class LoopbackServerJvmTest {
    @Test
    fun theCallbackServerBindsLoopbackAndNothingElse() {
        LoopbackServer(0).use { server ->
            val address = server.boundAddress
            assertTrue(address.isLoopbackAddress, "bound $address, which other machines can reach")
            assertTrue(server.redirectUri.startsWith("http://127.0.0.1:${server.port}/"))
        }
    }
}
