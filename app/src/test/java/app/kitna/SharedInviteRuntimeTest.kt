package app.kitna

import app.kitna.ui.sharedInviteToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedInviteRuntimeTest {
    @Test fun joinAcceptsFormattedLowerCaseAndSpacedGroupCodes() {
        assertEquals("ABCDEFGH2345", sharedInviteToken(" abcd-efgh-2345 "))
        assertEquals("ABCDEFGH2345", sharedInviteToken("ABCD EFGH 2345"))
        assertTrue(runCatching { sharedInviteToken("ABCD-IO01-2345") }.isFailure)
    }
    @Test fun joinAcceptsBareTokensDeepLinksAndTheWholeSharedMessage() {
        val token = "0123456789abcdef0123456789abcdef"
        val link = "app.kitna://join?token=$token"
        assertEquals(token, sharedInviteToken("  $token  "))
        assertEquals(token, sharedInviteToken(link))
        assertEquals(token, sharedInviteToken("Join Goa on Kitna.\n$link\nPaste this invitation in Shared groups."))
        assertTrue(runCatching { sharedInviteToken("https://example.com/not-an-invite") }.isFailure)
        assertTrue(runCatching { sharedInviteToken("app.kitna://join?token=short") }.isFailure)
        assertTrue(runCatching { sharedInviteToken("app.kitna://join?token=../../private") }.isFailure)
    }
}
