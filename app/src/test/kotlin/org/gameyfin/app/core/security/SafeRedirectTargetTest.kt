package org.gameyfin.app.core.security

import kotlin.test.Test
import kotlin.test.assertEquals

class SafeRedirectTargetTest {
    @Test
    fun `from accepts local application paths`() {
        assertEquals("/library/42?view=grid", SafeRedirectTarget.from("/library/42?view=grid"))
    }

    @Test
    fun `from defaults external and encoded redirect bypasses to home`() {
        listOf(
            "https://attacker.example",
            "//attacker.example",
            "/\\attacker.example",
            "/%2f%2fattacker.example",
            "javascript:alert(1)",
            "\u0000/library"
        ).forEach { target -> assertEquals("/", SafeRedirectTarget.from(target)) }
    }

    @Test
    fun `asQueryParameter keeps a local target as one parameter`() {
        assertEquals("%2Flibrary%2F42%3Fview%3Dgrid", SafeRedirectTarget.asQueryParameter("/library/42?view=grid"))
    }
}
