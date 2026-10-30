package org.gameyfin.app.users.avatar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.attribute.PosixFilePermission.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class AvatarOperatorControlTest {
    @Test fun `only exact fixed commands dispatch and reload failure revokes`() {
        var revoked = 0; var reloaded = 0
        fun run(value: String, fail: Boolean = false) = AvatarOperatorControl.command(
            value.toByteArray(), { "STATUS fixture\n" }, { revoked++ },
            { reloaded++; if (fail) error("synthetic-private-path-secret") })
        assertEquals("STATUS fixture\n", run("STATUS\n"))
        for (invalid in listOf("STATUS", "STATUS\r\n", "RELOAD /path\n", "RELOAD\nSTATUS\n", "", "status\n"))
            assertEquals("DENIED\n", run(invalid))
        assertEquals(0, revoked); assertEquals(0, reloaded)
        assertEquals("OK\n", run("RELOAD\n")); assertEquals(1, reloaded)
        assertEquals("DENIED\n", run("RELOAD\n", fail = true)); assertEquals(1, revoked)
        assertEquals("OK\n", run("REVOKE\n")); assertEquals(2, revoked)
    }

    @Test
    fun `inspection accepts only one bounded positive native ID and hides failures`() {
        var inspected: Long? = null
        fun run(input: String) = AvatarOperatorControl.command(input.toByteArray(), { fail("status") },
            { fail("revoke") }, { fail("reload") }, { id -> inspected = id; if (id == 2L) error("private-identity-secret"); "INVENTORY fixture\n" })
        assertEquals("INVENTORY fixture\n", run("INSPECT 1\n")); assertEquals(1L, inspected)
        for (input in listOf("INSPECT 0\n", "INSPECT -1\n", "INSPECT 01\n", "INSPECT 9223372036854775808\n",
            "INSPECT 1\r\n", "INSPECT 1\nSTATUS\n", "INSPECT 1 ", "INSPECT 1")) {
            assertEquals("DENIED\n", run(input)); assertEquals(1L, inspected)
        }
        assertEquals("DENIED\n", run("INSPECT 2\n")); assertEquals(2L, inspected)
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    fun `unsupported Windows owner control refuses without deleting existing files`(@TempDir root: Path) {
        val retained = Files.createFile(root.resolve("retained-fixture"))
        var revoked = 0
        val control = AvatarOperatorControl(root, Files.getOwner(root), { "STATUS\n" }, { revoked++ }, { fail("reload") })
        assertThrows(AvatarAuthority.Denied::class.java) { control.start() }
        assertTrue(revoked > 0)
        control.close()
        assertTrue(Files.exists(retained))
        assertFalse(Files.exists(root.resolve("avatar-control.sock")))
    }

    @Test @EnabledOnOs(OS.LINUX)
    fun `real private peer control dispatches and cleans only its owned socket`(@TempDir root: Path) {
        Files.setPosixFilePermissions(root, setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))
        val retained = Files.writeString(root.resolve("retained-fixture"), "retained")
        val revoked = AtomicInteger(); val reloaded = AtomicInteger()
        val control = AvatarOperatorControl(root, Files.getOwner(root), { "STATUS fixture\n" },
            { revoked.incrementAndGet() }, { reloaded.incrementAndGet() }, { id -> "INVENTORY $id é\n" })
        fun exchange(command: String): String = SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
            client.connect(UnixDomainSocketAddress.of(root.resolve("avatar-control.sock")))
            client.configureBlocking(false)
            val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            val request = ByteBuffer.wrap(command.toByteArray())
            while (request.hasRemaining()) {
                client.write(request); check(System.nanoTime() < deadline); Thread.sleep(1)
            }
            val response = ByteBuffer.allocate(512)
            while (response.position() == 0 || response.get(response.position() - 1) != 10.toByte()) {
                check(client.read(response) >= 0 && System.nanoTime() < deadline); Thread.sleep(1)
            }
            String(response.array(), 0, response.position(), Charsets.UTF_8)
        }
        try {
            control.start()
            assertEquals("STATUS fixture\n", exchange("STATUS\n"))
            assertEquals("INVENTORY 9223372036854775807 é\n", exchange("INSPECT 9223372036854775807\n"))
            assertEquals("OK\n", exchange("RELOAD\n")); assertEquals(1, reloaded.get())
            assertEquals("DENIED\n", exchange("BAD\n"))
            assertEquals("OK\n", exchange("REVOKE\n"))
        } finally { control.close() }
        assertTrue(revoked.get() > 0)
        assertEquals("retained", Files.readString(retained))
        assertFalse(Files.exists(root.resolve("avatar-control.sock")))
        // Existing unknown paths are never deleted to make startup succeed.
        val existing = Files.writeString(root.resolve("avatar-control.sock"), "not-owned")
        val refused = AvatarOperatorControl(root, Files.getOwner(root), { "STATUS\n" }, {}, {})
        assertThrows(AvatarAuthority.Denied::class.java) { refused.start() }
        assertEquals("not-owned", Files.readString(existing))
    }
}
