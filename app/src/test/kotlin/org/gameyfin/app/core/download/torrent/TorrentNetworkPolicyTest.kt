package org.gameyfin.app.core.download.torrent

import org.junit.jupiter.api.Test
import kotlin.test.*

class TorrentNetworkPolicyTest {
    private val primary = "https://tracker.invalid/announce?passkey=synthetic"
    private val secondary = "https://backup.invalid/private/announce"
    private fun policy(urls: Set<String> = setOf(primary, secondary)) = TorrentNetworkPolicy(urls, true)
    private fun metadata(): MutableMap<String, Any> = mutableMapOf(
        "info" to mapOf("files" to emptyList<Any>(), "name" to "Owned".toByteArray(), "piece length" to 16384L, "pieces" to byteArrayOf(), "private" to 1L),
        "announce" to primary.toByteArray()
    )

    @Test fun `explicit approved HTTPS primary and bounded secondary tiers pass without resolving hosts`() {
        policy().validate(metadata())
        policy().validate(metadata().apply {
            this["announce-list"] = listOf(listOf(primary.toByteArray()), listOf(secondary.toByteArray()))
            this["comment"] = "synthetic fixture".toByteArray()
            this["created by"] = "fixture".toByteArray()
            this["creation date"] = 0L
            this["encoding"] = "UTF-8".toByteArray()
        })
    }

    @Test fun `private admission acknowledgement and nonempty bounded approval configuration required`() {
        assertFailsWith<IllegalArgumentException> { TorrentNetworkPolicy(setOf(primary), false) }
        assertFailsWith<IllegalArgumentException> { TorrentNetworkPolicy(emptySet(), true) }
        assertFailsWith<IllegalArgumentException> { TorrentNetworkPolicy((1..9).map { "https://tracker$it.invalid/announce" }.toSet(), true) }
    }

    @Test fun `all primary and secondary trackers must match configuration byte for byte`() {
        for (url in listOf("https://tracker.invalid/announce?passkey=other", "https://TRACKER.invalid/announce?passkey=synthetic", "https://tracker.invalid:443/announce?passkey=synthetic", "https://foreign.invalid/announce")) {
            assertFails { policy().validate(metadata().apply { this["announce"] = url.toByteArray() }) }
            assertFails { policy().validate(metadata().apply { this["announce-list"] = listOf(listOf(primary.toByteArray(), url.toByteArray())) }) }
        }
    }

    @Test fun `webseeds unknown extensions and nested unsupported info fields are refused`() {
        for (field in listOf("url-list", "httpseeds", "nodes", "publisher-url", "x-private-network", "announce_list")) {
            assertFails { policy().validate(metadata().apply { this[field] = "https://foreign.invalid/secret".toByteArray() }) }
        }
        val root = metadata()
        root["info"] = (root.getValue("info") as Map<*, *>) + ("url-list" to "https://foreign.invalid/seed".toByteArray())
        assertFails { policy().validate(root) }
    }

    @Test fun `insecure opaque credential fragment malformed and invalid port URLs are refused`() {
        val invalid = listOf("http://tracker.invalid/announce", "udp://tracker.invalid:6969/announce", "file:///announce", "https:announce", "https://user:secret@tracker.invalid/announce", "https://tracker.invalid/announce#secret", "https://tracker.invalid:0/announce", "https://tracker.invalid:65536/announce", "https://tracker.invalid:/announce", "https://tracker.invalid:0443/announce", "https://tracker.invalid", "https://tracker.invalid/bad%ZZ?secret=token", "https://tracker.invalid/with space", "https://tracker.invalid/\u0000secret", "https://tracker.invalid/" + "x".repeat(2048))
        for (url in invalid) {
            val failure = assertFails { policy(setOf(url)) }
            assertFalse(failure.message.orEmpty().contains("secret"), "Errors must not disclose URLs or passkeys")
        }
    }

    @Test fun `malformed tracker types tier bounds duplicate and omitted primary fail closed`() {
        val tierCases: List<Any> = listOf(byteArrayOf(), emptyList<Any>(), listOf(emptyList<Any>()), List(9) { listOf(primary.toByteArray()) }, listOf(List(9) { primary.toByteArray() }), listOf(listOf(primary.toByteArray(), primary.toByteArray())), listOf(listOf(secondary.toByteArray())), listOf(listOf("not-bytes")), listOf(listOf(ByteArray(2049))))
        for (tiers in tierCases) assertFails { policy().validate(metadata().apply { this["announce-list"] = tiers }) }
        assertFails { policy().validate(metadata().apply { remove("announce") }) }
        assertFails { policy().validate(metadata().apply { this["announce"] = primary }) }
    }

    @Test fun `private bit descriptive type and descriptive size are required separately`() {
        val root = metadata()
        root["info"] = (root.getValue("info") as Map<*, *>) + ("private" to 0L)
        assertFails { policy().validate(root) }
        assertFails { policy().validate(metadata().apply { this["comment"] = ByteArray(4097) }) }
        assertFails { policy().validate(metadata().apply { this["encoding"] = mapOf("url-list" to primary) }) }
        assertFails { policy().validate(metadata().apply { this["creation date"] = -1L }) }
    }

    @Test fun `administrator may approve exact HTTPS private address without inferred routing trust`() {
        val lan = "https://192.0.2.1:8443/private/announce"
        val root = metadata().apply { this["announce"] = lan.toByteArray() }
        policy(setOf(lan)).validate(root)
        assertFails { policy().validate(root) }
    }
}
