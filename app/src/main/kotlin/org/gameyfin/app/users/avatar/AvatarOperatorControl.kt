package org.gameyfin.app.users.avatar

import jdk.net.ExtendedSocketOptions
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission.*
import java.nio.file.attribute.UserPrincipal
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** One private local command at a time; no network listener, polling or bearer input.
 * Root must be independently provisioned and excluded from application DB restores.
 * Same OS principal is the trust boundary, not a browser/admin session.
 * Two seconds bounds socket IO/admission, not DB-backed revocation/reload;
 * those wait for already admitted transactions to release authority leases.
 */
internal class AvatarOperatorControl(
    private val root: Path, private val owner: UserPrincipal,
    private val status: () -> String, private val revoke: () -> Unit, private val reload: () -> Unit,
    private val inspect: (Long) -> String = { "DENIED\n" }
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var listener: ServerSocketChannel? = null
    private var worker: Thread? = null
    @Volatile private var active: SocketChannel? = null
    private var socketKey: Any? = null
    private var rootKey: Any? = null
    private val dispatch = Any()
    private val socket = root.resolve("avatar-control.sock")

    @Synchronized fun start() {
        check(listener == null && worker == null)
        try {
            require(root.isAbsolute && root == root.normalize())
            rootKey = checkRoot()
            SocketChannel.open(StandardProtocolFamily.UNIX).use {
                require(ExtendedSocketOptions.SO_PEERCRED in it.supportedOptions())
            }
            // Never remove a stale socket or another process's path on startup.
            require(!Files.exists(socket, NOFOLLOW_LINKS))
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            listener = server
            server.bind(UnixDomainSocketAddress.of(socket), 1)
            val created = Files.readAttributes(socket, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            require(created.isOther && created.owner() == owner && created.fileKey() != null)
            socketKey = created.fileKey()
            Files.setPosixFilePermissions(socket, setOf(OWNER_READ, OWNER_WRITE))
            require(checkRoot() == rootKey)
            running.set(true)
            worker = Thread({ serve(server) }, "avatar-operator-control").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            close()
            throw AvatarAuthority.Denied()
        }
    }

    private fun checkRoot(): Any {
        // Reject symlink components. The final private directory is owned by the
        // attested principal; callers must not share that principal with untrusted apps.
        var cursor = root.root
        for (part in root) {
            cursor = cursor.resolve(part)
            require(!Files.isSymbolicLink(cursor))
        }
        val attributes = Files.readAttributes(root, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isDirectory && attributes.owner() == owner && attributes.fileKey() != null &&
            attributes.permissions() == setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))
        Files.newDirectoryStream(root).use { require(it is SecureDirectoryStream<*>) }
        return requireNotNull(attributes.fileKey())
    }

    private fun serve(server: ServerSocketChannel) {
        try {
            while (running.get()) {
                server.accept().use { client ->
                    active = client
                    try {
                        require(checkRoot() == rootKey)
                        require(ExtendedSocketOptions.SO_PEERCRED in client.supportedOptions())
                        require(client.getOption(ExtendedSocketOptions.SO_PEERCRED).user() == owner)
                        client.configureBlocking(false)
                        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
                        val command = readCommand(client, deadline)
                        val response = synchronized(dispatch) {
                            require(running.get() && System.nanoTime() < deadline)
                            command(command, status, revoke, reload, inspect)
                        }
                        write(client, response.toByteArray(Charsets.UTF_8), deadline)
                    } catch (_: Exception) {
                        // No path, peer, configuration or exception disclosure.
                    } finally { active = null }
                }
            }
        } catch (_: Exception) {
            // Listener failure leaves capability revoked, never silently enabled.
        } finally {
            running.set(false)
            revoke()
        }
    }

    private fun readCommand(client: SocketChannel, deadline: Long): ByteArray {
        val bytes = ByteBuffer.allocate(32) // Includes INSPECT + one positive Long + newline.
        while (true) {
            val count = client.read(bytes)
            require(count >= 0)
            if (bytes.position() > 0 && bytes.get(bytes.position() - 1) == 10.toByte()) {
                return bytes.array().copyOf(bytes.position())
            }
            require(bytes.hasRemaining())
            await(client, SelectionKey.OP_READ, deadline)
        }
    }
    private fun write(client: SocketChannel, bytes: ByteArray, deadline: Long) {
        require(bytes.size <= 32768)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (client.write(buffer) == 0) await(client, SelectionKey.OP_WRITE, deadline)
            require(System.nanoTime() < deadline)
        }
    }
    private fun await(client: SocketChannel, operation: Int, deadline: Long) {
        val remaining = deadline - System.nanoTime()
        require(remaining > 0 && running.get())
        Selector.open().use { selector ->
            client.register(selector, operation)
            selector.select((remaining / 1_000_000).coerceAtLeast(1))
        }
        require(System.nanoTime() < deadline)
    }

    override fun close() {
        running.set(false)
        try { listener?.close() } catch (_: Exception) { }
        try { active?.close() } catch (_: Exception) { }
        synchronized(dispatch) { revoke() }
        // Delete ONLY the exact socket created by this owner, through a verified
        // secure directory handle. Unknown/stale/replaced paths are retained.
        try {
            if (socketKey != null && checkRoot() == rootKey) {
                Files.newDirectoryStream(root).use { opened ->
                    val directory = opened as SecureDirectoryStream<Path>
                    val view = directory.getFileAttributeView(Path.of("avatar-control.sock"),
                        java.nio.file.attribute.PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                    val current = view.readAttributes()
                    if (current.fileKey() == socketKey && current.isOther && current.owner() == owner)
                        directory.deleteFile(Path.of("avatar-control.sock"))
                }
            }
        } catch (_: Exception) { }
        worker?.takeIf { it !== Thread.currentThread() }?.let {
            it.join(3000)
            check(!it.isAlive) { "Avatar operator shutdown incomplete" }
        }
    }

    companion object {
        /** Exact ASCII grammar; no arbitrary path/config/secret accepted. */
        internal fun command(bytes: ByteArray, status: () -> String,
                             revoke: () -> Unit, reload: () -> Unit,
                             inspect: (Long) -> String = { "DENIED\n" }): String = when {
            bytes.contentEquals("STATUS\n".toByteArray()) -> status()
            bytes.contentEquals("REVOKE\n".toByteArray()) -> { revoke(); "OK\n" }
            bytes.contentEquals("RELOAD\n".toByteArray()) -> {
                try { reload(); "OK\n" } catch (_: Exception) { revoke(); "DENIED\n" }
            }
            else -> {
                val input = bytes.toString(Charsets.US_ASCII)
                val id = Regex("INSPECT ([1-9][0-9]{0,18})\n").matchEntire(input)
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (id == null) "DENIED\n"
                else try { inspect(id) } catch (_: Exception) { "DENIED\n" }
            }
        }
    }
}
