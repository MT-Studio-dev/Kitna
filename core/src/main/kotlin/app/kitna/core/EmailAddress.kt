package app.kitna.core

import java.util.Locale

/** Optional on a local friend; shared invitations only trust the server's verified identity. */
object EmailAddress {
    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).also { email ->
        require(email.isEmpty() || (email.length <= 254 && email.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}")))) {
            "Enter a valid email address, or leave it blank for a local friend."
        }
    }
}
