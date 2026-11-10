package app.healthquery.sync

import java.security.MessageDigest

/** Stable, non-secret batch identity scoped to one enrolled device (or legacy installation). */
object BatchIdPolicy {
    fun digest(scopeId: String, canonicalRequest: String): String {
        require(scopeId.isNotBlank())
        return MessageDigest.getInstance("SHA-256")
            .digest((scopeId + "\n" + canonicalRequest).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
