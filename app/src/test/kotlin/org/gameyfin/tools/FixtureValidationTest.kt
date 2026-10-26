package org.gameyfin.tools

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Daemon-free rejection contracts; these never stand in for real provider/proxy acceptance. */
class FixtureValidationTest {
    @Test fun `images require exact config IDs or digest references never mutable tags`() {
        val hash = "a".repeat(64)
        requireFixtureImage("sha256:$hash")
        requireFixtureImage("ghcr.io/niborian/gameyfin@sha256:$hash")
        for (image in listOf("gameyfin:latest", "gameyfin:2.4.7", "sha256:abc", "sha256:${"A".repeat(64)}",
            "@sha256:$hash", "sha256:$hash --privileged", "sha256:$hash\n")) assertFails { requireFixtureImage(image) }
    }
    @Test fun `tmpfs is admitted only for exact explicit bounded nonroot readonly browser intent`() {
        val root = Path.of("/owned/fixture").toAbsolutePath().normalize()
        fun sample(options: String = "rw,nosuid,nodev,size=128m,mode=1777", user: String = "10042:10042", readonly: Boolean = true,
                   destination: String = "/tmp", type: String = "tmpfs") = FixtureRuntime.tree("""{"Config":{"User":"$user"},"HostConfig":{"ReadonlyRootfs":$readonly,"Tmpfs":{"$destination":"$options"}},"Mounts":[{"Type":"$type","Destination":"$destination"}]}""")
        verifyFixtureMountPolicy(sample(), root, true)
        verifyFixtureMountPolicy(sample("mode=1777,nodev,size=134217728,nosuid,rw"), root, true)
        assertFails { verifyFixtureMountPolicy(sample(), root, false) }
        for (options in listOf("rw,nosuid,size=128m,mode=1777", "rw,nosuid,nodev,size=1g,mode=1777", "rw,nosuid,nodev,size=128m,mode=1777,exec", "rw,nosuid,nodev,size=128m,rw"))
            assertFails { verifyFixtureMountPolicy(sample(options), root, true) }
        assertFails { verifyFixtureMountPolicy(sample(user = "0:0"), root, true) }
        assertFails { verifyFixtureMountPolicy(sample(readonly = false), root, true) }
        assertFails { verifyFixtureMountPolicy(sample(destination = "/other"), root, true) }
        assertFails { verifyFixtureMountPolicy(sample(type = "volume"), root, true) }
        assertFails { verifyFixtureMountPolicy(FixtureRuntime.tree("""{"HostConfig":{},"Mounts":[{"Type":"bind","Source":"/foreign"}]}"""), root, false) }
        verifyFixtureMountPolicy(FixtureRuntime.tree("""{"HostConfig":{},"Mounts":[{"Type":"bind","Source":"/owned/fixture/script.cts"}]}"""), root, false)
    }
    private fun zip(vararg members: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive -> for ((name, bytes) in members) { archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry() } }
        return output.toByteArray()
    }
    @Test fun `grouped optional archive names hashes and expanded limit are exact`() {
        val a = "a".toByteArray(); val b = "b".toByteArray(); val patch = "patch".toByteArray()
        for (members in listOf(arrayOf("base-a" to a, "base-b" to b), arrayOf("base-a" to a, "base-b" to b, "patch" to patch))) {
            ProxyExposureFixture.archive(zip(*members), members.associate { it.first to FixtureRuntime.sha(it.second) })
        }
        assertFails { ProxyExposureFixture.archive(zip("base" to a, "extra" to b), mapOf("base" to FixtureRuntime.sha(a))) }
        assertFails { ProxyExposureFixture.archive(zip("base" to a), mapOf("base" to "0".repeat(64))) }
        assertFails { ProxyExposureFixture.archive(zip("base" to ByteArray(1024 * 1024 + 1)), mapOf("base" to "0".repeat(64))) }
        assertFails { ProxyExposureFixture.archive(zip("base" to a), emptyMap()) }
        assertFails { ProxyExposureFixture.archive(zip("base" to a), mapOf("base" to FixtureRuntime.sha(a), "missing" to FixtureRuntime.sha(b))) }
        val duplicate = zip("first" to a, "other" to b)
        val needle = "other".toByteArray(); val replacement = "first".toByteArray()
        for (index in 0..duplicate.size - needle.size) if (needle.indices.all { duplicate[index + it] == needle[it] }) replacement.copyInto(duplicate, index)
        assertFails { ProxyExposureFixture.archive(duplicate, mapOf("first" to FixtureRuntime.sha(a))) }
    }
    @Test fun `manifest may target only same origin synthetic download paths`() {
        for (path in listOf("https://foreign.invalid/download/1", "//foreign.invalid/download/1", "/connect/OtherEndpoint/write", "/download/%2e%2e/other", "/download/a%5cb")) assertFails { ProxyExposureFixture.downloadPath(path) }
        assertEquals("/download/1?optional=true", ProxyExposureFixture.downloadPath("/download/1?optional=true"))
        assertFails { ProxyExposureFixture.csrf("<html>no token</html>") }
    }
    @Test fun `proxy runtime binding refuses wildcard wrong port extra port and empty`() {
        val invalid = listOf("{\"8080/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"39080\"}]}", "{\"8080/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"39081\"}]}", "{\"8080/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"39080\"}],\"8081/tcp\":[]}", "{}")
        for (value in invalid) assertFails { ProxyExposureFixture.runtimeBindings(FixtureRuntime.tree(value), 39080) }
        ProxyExposureFixture.runtimeBindings(FixtureRuntime.tree("{\"8080/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"39080\"}]}"), 39080)
    }
    @Test fun `logout requires actual anonymous same origin login redirect`() {
        assertFails { ProxyExposureFixture.loggedOut(200, null, "http://127.0.0.1:39080") }
        assertFails { ProxyExposureFixture.loggedOut(302, "https://foreign.invalid/login", "http://127.0.0.1:39080") }
        ProxyExposureFixture.loggedOut(302, "/login", "http://127.0.0.1:39080")
    }
    @Test fun `configuration refuses exposed backend and bind mounted production sources`() {
        val safe = "{\"services\":{\"gameyfin\":{\"volumes\":[{\"type\":\"volume\"}]},\"proxy\":{\"ports\":[{\"host_ip\":\"127.0.0.1\",\"published\":\"39080\"}]}}}"
        assertEquals(39080, ExposureFixtureRehearsal.configuration(FixtureRuntime.tree(safe)))
        assertFails { ExposureFixtureRehearsal.configuration(FixtureRuntime.tree(safe.replace("\"volume\"", "\"bind\""))) }
        assertFails { ExposureFixtureRehearsal.configuration(FixtureRuntime.tree(safe.replace("127.0.0.1", "0.0.0.0"))) }
    }
    @Test fun `creator parser rejects duplicate keys malformed integers lengths trailing and depth budget`() {
        for (bytes in listOf("d1:ai1e1:ai2ee", "i01e", "3:ab", "0:extra", "l".repeat(18) + "e".repeat(18)).map { it.toByteArray() } + listOf(ByteArray(1024 * 1024 + 1))) assertFails { CreatorMetadata.decode(bytes) }
        assertEquals(mapOf("key" to listOf(1L, "v".toByteArray())).keys, (CreatorMetadata.decode("d3:keyli1e1:vee".toByteArray()) as Map<*, *>).keys)
    }
    @Test fun `creator piece mismatch remains refusal`() {
        val metadata = "d4:infod5:filesld6:lengthi1e4:pathl1:aeee4:name1:x12:piece lengthi16384e6:pieces20:xxxxxxxxxxxxxxxxxxxx7:privatei1eee".toByteArray()
        assertFails { CreatorMetadata.verify(metadata, mapOf("a" to "x".toByteArray()), "x") }
    }
    @Test fun `recursive cleanup refuses unregistered matching names`() {
        assertFails { FixtureRuntime.deleteOwned(Path.of(System.getProperty("java.io.tmpdir"), "gameyfin-unregistered").toAbsolutePath()) }
    }
    @Test fun `context permits full synthetic manifest but refuses oversized and nonboolean internal policy`() {
        val large = "{\"manifest\":\"" + "a".repeat(70000) + "\"}"
        assertEquals(70000, ProxyExposureFixture.context(large)["manifest"].asText().length)
        assertFails { ProxyExposureFixture.context(" ".repeat(1024 * 1024 + 1)) }
        for (value in listOf("{}", "{\"Internal\":false}", "{\"Internal\":\"true\"}", "{\"Internal\":1}")) assertFails { ProxyExposureFixture.internalNetwork(FixtureRuntime.tree(value)) }
        ProxyExposureFixture.internalNetwork(FixtureRuntime.tree("{\"Internal\":true}"))
    }
    @Test fun `response subscriber cancels immediately beyond bounded bytes`() {
        var cancelled = false
        val subscription = object : java.util.concurrent.Flow.Subscription { override fun request(n: Long) {} ; override fun cancel() { cancelled = true } }
        val subscriber = FixtureHttp.LimitedBody(3); subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))))
        assertTrue(cancelled); assertTrue(subscriber.body.toCompletableFuture().isCompletedExceptionally)
    }
}
