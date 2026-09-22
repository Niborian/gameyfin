package org.gameyfin.app.core.security

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Restricts post-login navigation to a local application path. */
object SafeRedirectTarget {
    private val encodedControl = Regex("%0[0-9a-f]|%1[0-9a-f]|%7f", RegexOption.IGNORE_CASE)

    fun from(value: String?): String {
        if (value.isNullOrBlank() || !value.startsWith("/") || value.startsWith("//")) return "/"
        if (value.contains('\\') || value.any { it.isISOControl() }) return "/"
        if (value.contains("%2f", ignoreCase = true) || value.contains("%5c", ignoreCase = true)) return "/"
        if (encodedControl.containsMatchIn(value)) return "/"

        return try {
            val uri = URI(value)
            if (uri.isAbsolute || uri.rawAuthority != null) "/" else value
        } catch (_: Exception) {
            "/"
        }
    }

    fun asQueryParameter(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
