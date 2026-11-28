package app.kitna

import android.net.Uri
import app.kitna.data.InvitationLinks
import app.kitna.data.PendingInvitation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InvitationRoutingRuntimeTest {
    @Test fun groupAndFriendLinksRoundTripWithDistinctSemantics() {
        val token = "ab".repeat(32)
        listOf("group", "friend").forEach { kind -> assertEquals(PendingInvitation(kind, token), InvitationLinks.parse(Uri.parse(InvitationLinks.url(kind, token)))) }
        assertEquals(PendingInvitation("group", "ABCD-EFGH-2345"), InvitationLinks.parse(Uri.parse(InvitationLinks.url("group", "ABCD-EFGH-2345"))))
    }
    @Test fun unknownHostsPathsAndMalformedSecretsCannotBecomePendingInvitations() {
        listOf("https://evil.example/invite/group/" + "a".repeat(64), "app.kitna://join/other?token=" + "a".repeat(64), "app.kitna://join?token=../../private", "app.kitna://auth/callback?token=" + "a".repeat(64), "app.kitna://friend?token=short", "app.kitna://join?token=" + "a".repeat(301)).forEach { assertNull(it, InvitationLinks.parse(Uri.parse(it))) }
    }
}
