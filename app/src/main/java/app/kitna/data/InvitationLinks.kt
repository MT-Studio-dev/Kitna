package app.kitna.data

import android.net.Uri
import app.kitna.BuildConfig

data class PendingInvitation(val kind: String, val token: String)

/** Accept only our explicit routes. Arbitrary web URLs never become bearer invitations. */
object InvitationLinks {
    private val tokenPattern = Regex("[A-Za-z0-9_-]{12,300}")
    fun parse(uri: Uri): PendingInvitation? {
        val kind: String
        val token: String
        if (uri.scheme == "app.kitna" && uri.host in setOf("join", "friend") && uri.path.orEmpty() in setOf("", "/")) {
            kind = if (uri.host == "friend") "friend" else "group"
            token = uri.getQueryParameter("token") ?: return null
        } else if (uri.scheme == "https" && BuildConfig.INVITE_HOST.isNotBlank() && uri.host == BuildConfig.INVITE_HOST && uri.port == -1) {
            val parts = uri.pathSegments
            if (parts.size != 3 || parts[0] != "invite" || parts[1] !in setOf("group", "friend")) return null
            kind = parts[1]; token = parts[2]
        } else return null
        return token.takeIf { tokenPattern.matches(it) }?.let { PendingInvitation(kind, it) }
    }
    fun url(kind: String, token: String): String {
        require(kind in setOf("group", "friend") && tokenPattern.matches(token))
        return if (BuildConfig.INVITE_HOST.isBlank()) Uri.Builder().scheme("app.kitna").authority(if (kind == "group") "join" else "friend").appendQueryParameter("token", token).build().toString()
        else Uri.Builder().scheme("https").authority(BuildConfig.INVITE_HOST).appendPath("invite").appendPath(kind).appendPath(token).build().toString()
    }
}
