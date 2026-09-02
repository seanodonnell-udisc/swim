package swim.core.auth

import kotlinx.coroutines.test.runTest
import swim.core.Canned
import swim.core.HttpRecorder
import swim.core.model.AuthError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CODE_RESPONSE =
    """{"device_code":"dc-1","user_code":"WXYZ-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""

class GithubDeviceFlowTest {
    @Test
    fun pollsThroughPendingAndSlowDownToTheToken() = runTest {
        val recorder = HttpRecorder(
            Canned(CODE_RESPONSE),
            Canned("""{"error":"authorization_pending"}"""),
            Canned("""{"error":"slow_down","interval":10}"""),
            Canned("""{"access_token":"gho_token","token_type":"bearer","scope":"repo"}"""),
        )
        val flow = GithubDeviceFlow(recorder.client, "client-id")

        val code = flow.requestCode()
        assertEquals("dc-1", code.deviceCode)
        assertEquals("WXYZ-1234", code.userCode)
        assertEquals(5L, code.intervalSeconds)

        assertEquals("gho_token", flow.awaitToken(code))
        assertEquals(4, recorder.requests.size)
        assertContains(recorder.bodies[0], "scope=repo")
        assertTrue(recorder.bodies[1].contains("device_code=dc-1"))
        assertTrue(recorder.bodies[1].contains("grant_type="))
    }

    @Test
    fun anExpiredCodeSaysToStartAgain() = runTest {
        val recorder = HttpRecorder(Canned("""{"error":"expired_token"}"""))
        val flow = GithubDeviceFlow(recorder.client, "client-id")
        val error = assertFailsWith<AuthError> { flow.awaitToken(deviceCode()) }
        assertContains(error.message.orEmpty(), "expired")
    }

    @Test
    fun aCancelledSignInSaysSo() = runTest {
        val recorder = HttpRecorder(Canned("""{"error":"access_denied"}"""))
        val flow = GithubDeviceFlow(recorder.client, "client-id")
        val error = assertFailsWith<AuthError> { flow.awaitToken(deviceCode()) }
        assertContains(error.message.orEmpty(), "cancelled")
    }

    @Test
    fun disabledDeviceFlowNamesTheAppSetting() = runTest {
        val recorder = HttpRecorder(Canned("""{"error":"device_flow_disabled"}"""))
        val flow = GithubDeviceFlow(recorder.client, "client-id")
        val error = assertFailsWith<AuthError> { flow.requestCode() }
        assertContains(error.message.orEmpty(), "device flow")
    }
}

private fun deviceCode() = GithubDeviceCode(
    deviceCode = "dc-1",
    userCode = "WXYZ-1234",
    verificationUri = "https://github.com/login/device",
    expiresInSeconds = 900,
    intervalSeconds = 5,
)
