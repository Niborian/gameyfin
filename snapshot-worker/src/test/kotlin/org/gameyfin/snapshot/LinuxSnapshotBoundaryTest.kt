package org.gameyfin.snapshot

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*

/** Native acceptance must run on the exact reviewed Linux head; Windows skips are not proof. */
@EnabledOnOs(OS.LINUX)
class LinuxSnapshotBoundaryTest {
    private fun fixture(action: (Path, Path) -> Unit) {
        // /tmp ancestry is intentionally refused by production cache policy. A private
        // user-home fixture exercises that policy rather than weakening it for tests.
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "snapshot-boundary-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val source = Files.createDirectory(root.resolve("source")); val cache = Files.createDirectory(root.resolve("cache"))
        try { action(source, cache) }
        finally {
            // Exact newly-created synthetic fixture only, including retained failed outputs.
            // Restore write permission on owned directories BEFORE deleting their children.
            Files.walk(root).use { it.forEach { path ->
                require(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            } }
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach { path ->
                require(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
                Files.delete(path)
            } }
        }
    }
    private fun capture(session: WorkerSession, source: Path, cache: Path, limit: Long = 1024,
        prepared: (SnapshotPreparation) -> Unit = {}): SnapshotCapture {
        val identity = SnapshotProtocol.decode(session.hello(cache.toString(), "")) { SnapshotProtocol.text(this) }
        return SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), source.toString(), cache.toString(), identity,
            listOf(SnapshotSelection("base.bin", "base")), limit, 8, 0).also {
                prepared(SnapshotPreparation.read(session.prepare(it, SnapshotProtocol.digest(it.bytes()))))
            }
    }
    @Test fun `capture reopen and persistent read preserve exact bytes`() = fixture { source, cache ->
        val original = "exact synthetic bytes".toByteArray(); Files.write(source.resolve("base.bin"), original)
        lateinit var request: SnapshotCapture; lateinit var encoded: ByteArray
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            native.root(source.toString(), owned = false).use { directory ->
                assertEquals(listOf("base.bin"), native.names(directory))
                assertEquals(listOf("base.bin"), native.names(directory), "Repeated descriptor enumeration must rewind")
            }
            request = capture(session, source, cache)
            encoded = session.capture(request, SnapshotProtocol.digest(request.bytes()))
            assertContentEquals(original, session.read("base", 0, 1024))
            val closedBefore = native.closedArgumentArenas
            repeat(128) {
                assertContentEquals(original, session.read("base", 0, 1024))
                assertEquals(0, native.liveArgumentArenas, "Every native argument scope must close after a read")
            }
            assertTrue(native.closedArgumentArenas > closedBefore)
        } }
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            session.hello(cache.toString(), request.expectedCacheIdentity)
            assertContentEquals(encoded, session.reopen(request.snapshotId, request.operation, SnapshotProtocol.digest(request.bytes())))
            assertContentEquals(original, session.read("base", 0, 1024))
        } }
        assertContentEquals(original, Files.readAllBytes(source.resolve("base.bin")))
    }
    @Test fun `A stat B open A restore is refused by opened descriptor identity`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "AAAA"); Files.writeString(source.resolve("other.bin"), "BBBB")
        val hooks = AcquisitionHooks(beforeSourceOpen = {
            Files.move(source.resolve("base.bin"), source.resolve("saved.bin"))
            Files.move(source.resolve("other.bin"), source.resolve("base.bin"))
        }, afterSourceOpen = {
            Files.move(source.resolve("base.bin"), source.resolve("other.bin"))
            Files.move(source.resolve("saved.bin"), source.resolve("base.bin"))
        })
        LinuxDescriptors().use { native -> WorkerSession(native, hooks).use { session ->
            val request = capture(session, source, cache)
            assertFails { session.capture(request, SnapshotProtocol.digest(request.bytes())) }
            assertFalse(Files.exists(cache.resolve(request.snapshotId.toString())))
        } }
        assertEquals("AAAA", Files.readString(source.resolve("base.bin")))
    }
    @Test fun `post-first-byte source truncation is refused without publication`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "AAAA")
        val hooks = AcquisitionHooks(afterWrite = { Files.write(source.resolve("base.bin"), byteArrayOf()) })
        LinuxDescriptors().use { native -> WorkerSession(native, hooks).use { session ->
            val request = capture(session, source, cache)
            assertFails { session.capture(request, SnapshotProtocol.digest(request.bytes())) }
            assertFalse(Files.exists(cache.resolve(request.snapshotId.toString())))
        } }
    }
    @Test fun `source hardlink is copied without modifying either original alias`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "original linked bytes")
        Files.createLink(source.resolve("torrent-managed-alias.bin"), source.resolve("base.bin"))
        val originalLinks = Files.getAttribute(source.resolve("base.bin"), "unix:nlink")
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val request = capture(session, source, cache)
            session.capture(request, SnapshotProtocol.digest(request.bytes()))
            assertContentEquals("original linked bytes".toByteArray(), session.read("base", 0, 1024))
        } }
        assertEquals(originalLinks, Files.getAttribute(source.resolve("base.bin"), "unix:nlink"))
        assertTrue(Files.isSameFile(source.resolve("base.bin"), source.resolve("torrent-managed-alias.bin")))
        assertEquals("original linked bytes", Files.readString(source.resolve("torrent-managed-alias.bin")))
    }
    @Test fun `grouped multipart basenames and optional content remain exact without source changes`() = fixture { source, cache ->
        val content = linkedMapOf("game.part01.rar" to "one", "game.part02.rar" to "two", "optional.bin" to "optional")
        content.forEach { (name, bytes) -> Files.writeString(source.resolve(name), bytes) }
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val identity = SnapshotProtocol.decode(session.hello(cache.toString(), "")) { SnapshotProtocol.text(this) }
            val request = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), source.toString(), cache.toString(), identity,
                listOf(SnapshotSelection("game.part01.rar", "content-1/game.part01.rar"), SnapshotSelection("game.part02.rar", "content-1/game.part02.rar")), 1024, 8, 0)
            session.prepare(request, SnapshotProtocol.digest(request.bytes()))
            val manifest = SnapshotManifest.read(session.capture(request, SnapshotProtocol.digest(request.bytes())))
            assertEquals(listOf("content-1/game.part01.rar", "content-1/game.part02.rar"), manifest.members.map { it.name })
            assertContentEquals("one".toByteArray(), session.read("content-1/game.part01.rar", 0, 1024))
            assertFalse(manifest.members.any { it.name.contains("optional") })
        } }
        content.forEach { (name, bytes) -> assertEquals(bytes, Files.readString(source.resolve(name))) }
    }
    @Test fun `worker guard stops post-write acquisition independently of caller thread interruption`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "AAAA")
        var ownerAlive = true
        LinuxDescriptors { check(ownerAlive) { "synthetic owner loss" } }.use { native ->
            WorkerSession(native, AcquisitionHooks(afterWrite = { ownerAlive = false })).use { session ->
                val request = capture(session, source, cache)
                assertFails { session.capture(request, SnapshotProtocol.digest(request.bytes())) }
                assertFalse(Files.exists(cache.resolve(request.snapshotId.toString())))
                assertEquals(0, native.liveArgumentArenas)
            }
        }
    }
    @Test fun `replaced source directory cannot redirect descendant opens into replacement tree`() = fixture { source, cache ->
        val selected = Files.createDirectory(source.resolve("tree")); Files.writeString(selected.resolve("base.bin"), "AUTHORIZED")
        val replacement = Files.createDirectory(source.resolve("replacement")); Files.writeString(replacement.resolve("base.bin"), "WRONG TREE")
        var swapped = false
        val hooks = AcquisitionHooks(afterDirectoryNames = {
            if (!swapped) {
                swapped = true
                Files.move(selected, source.resolve("retained-original"))
                Files.move(replacement, selected)
            }
        })
        LinuxDescriptors().use { native -> WorkerSession(native, hooks).use { session ->
            val identity = SnapshotProtocol.decode(session.hello(cache.toString(), "")) { SnapshotProtocol.text(this) }
            val request = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), source.toString(), cache.toString(), identity,
                listOf(SnapshotSelection("tree", "base")), 1024, 8, 0)
            session.prepare(request, SnapshotProtocol.digest(request.bytes()))
            val result = runCatching { session.capture(request, SnapshotProtocol.digest(request.bytes())) }
            if (result.isSuccess) assertContentEquals("AUTHORIZED".toByteArray(), session.read("base/base.bin", 0, 1024))
            else assertFalse(Files.exists(cache.resolve(request.snapshotId.toString())))
        } }
        assertEquals("WRONG TREE", Files.readString(selected.resolve("base.bin")))
    }
    @Test fun `renamed pending directory never publishes or deletes foreign replacement`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "AAAA")
        lateinit var request: SnapshotCapture
        var replacementInstalled = false
        val hooks = AcquisitionHooks(beforePublication = {
            val pending = cache.resolve("pending-${request.operation}")
            // JDK's non-atomic move checks W_OK on the source directory itself.
            // Atomic same-parent rename exercises the intended name-rebinding
            // race without loosening the immutable directory's permissions.
            Files.move(pending, cache.resolve("retained-owned"), ATOMIC_MOVE)
            Files.createDirectory(pending); Files.writeString(pending.resolve("sentinel"), "FOREIGN")
            replacementInstalled = true
        })
        LinuxDescriptors().use { native -> WorkerSession(native, hooks).use { session ->
            request = capture(session, source, cache)
            val failure = assertFailsWith<IllegalArgumentException> { session.capture(request, SnapshotProtocol.digest(request.bytes())) }
            assertTrue(replacementInstalled, "The replacement must exist before testing the worker rejection")
            assertEquals("Pending name rebound", failure.message)
            assertFalse(Files.exists(cache.resolve(request.snapshotId.toString())))
        } }
        assertEquals("FOREIGN", Files.readString(cache.resolve("pending-${request.operation}/sentinel")))
    }
    @Test fun `retirement removes only persisted owned snapshot and is idempotent`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "SOURCE MUST REMAIN")
        lateinit var prepared: SnapshotPreparation
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val request = capture(session, source, cache, prepared = { prepared = it })
            session.capture(request, SnapshotProtocol.digest(request.bytes()))
        } }
        repeat(2) {
            LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
                session.hello(cache.toString(), prepared.cacheIdentity)
                session.retire(prepared)
            } }
        }
        assertFalse(Files.exists(cache.resolve(prepared.snapshotId.toString())))
        assertEquals("SOURCE MUST REMAIN", Files.readString(source.resolve("base.bin")))
    }
    @Test fun `retirement refuses replacement identity without touching foreign files`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "SOURCE")
        lateinit var prepared: SnapshotPreparation
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val request = capture(session, source, cache, prepared = { prepared = it })
            session.capture(request, SnapshotProtocol.digest(request.bytes()))
        } }
        val original = cache.resolve(prepared.snapshotId.toString())
        val originalPermissions = Files.getPosixFilePermissions(original)
        val retained = cache.resolve("retained-original")
        Files.move(original, retained, ATOMIC_MOVE)
        assertEquals(originalPermissions, Files.getPosixFilePermissions(retained), "Rebinding must not chmod the owned snapshot")
        Files.createDirectory(original); Files.writeString(original.resolve("sentinel"), "FOREIGN")
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            session.hello(cache.toString(), prepared.cacheIdentity)
            assertFails { session.retire(prepared) }
        } }
        assertEquals("FOREIGN", Files.readString(original.resolve("sentinel")))
        assertEquals("SOURCE", Files.readString(source.resolve("base.bin")))
    }
    @Test fun `symlink source and byte quota fail closed`() = fixture { source, cache ->
        Files.writeString(source.resolve("outside"), "SENTINEL"); Files.createSymbolicLink(source.resolve("base.bin"), Path.of("outside"))
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val request = capture(session, source, cache)
            assertFails { session.capture(request, SnapshotProtocol.digest(request.bytes())) }
            assertFalse(Files.exists(cache.resolve(request.snapshotId.toString())))
        } }
        Files.delete(source.resolve("base.bin")); Files.writeString(source.resolve("base.bin"), "OVER QUOTA")
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val request = capture(session, source, cache, 1)
            assertFails { session.capture(request, SnapshotProtocol.digest(request.bytes())) }
            assertFalse(Files.exists(cache.resolve(request.snapshotId.toString())))
        } }
    }
    @Test fun `source verification is read only and distinguishes stable content and source generation changes`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "ORIGINAL")
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val request = capture(session, source, cache)
            session.capture(request, SnapshotProtocol.digest(request.bytes()))
            val cacheNames = Files.list(cache).use { it.map(Path::getFileName).toList() }
            fun proof() = SnapshotSourceProof.read(session.verifySource(request, SnapshotProtocol.digest(request.bytes())))
            val original = proof()
            assertEquals(SnapshotSourceVerification.MATCH, original.result)
            assertEquals(original, proof(), "Unchanged descriptors produce the same provenance without another COPY")
            Files.writeString(source.resolve("new-source.bin"), "ORIGINAL")
            Files.move(source.resolve("new-source.bin"), source.resolve("base.bin"), ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val replaced = proof()
            assertEquals(SnapshotSourceVerification.SAME_CONTENT_DIFFERENT_SOURCE, replaced.result)
            assertNotEquals(original.sourceDigest, replaced.sourceDigest)
            Files.writeString(source.resolve("base.bin"), "DIFFERENT")
            assertEquals(SnapshotSourceVerification.MISMATCH, proof().result)
            assertContentEquals("ORIGINAL".toByteArray(), session.read("base", 0, 1024), "Old referenced immutable cache is not changed by a source mismatch")
            assertEquals(cacheNames, Files.list(cache).use { it.map(Path::getFileName).toList() })
        } }
    }
    @Test fun `source proof instability and unsafe links throw rather than authorize a new generation`() = fixture { source, cache ->
        Files.writeString(source.resolve("base.bin"), "ORIGINAL")
        lateinit var request: SnapshotCapture
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            request = capture(session, source, cache); session.capture(request, SnapshotProtocol.digest(request.bytes()))
        } }
        var mutated = false
        LinuxDescriptors().use { native -> WorkerSession(native, AcquisitionHooks(afterSourceVerifyRead = {
            if (!mutated) { mutated = true; Files.writeString(source.resolve("base.bin"), "CHANGED!") }
        })).use { session ->
            session.hello(cache.toString(), request.expectedCacheIdentity)
            session.reopen(request.snapshotId, request.operation, SnapshotProtocol.digest(request.bytes()))
            assertFailsWith<IllegalArgumentException> { session.verifySource(request, SnapshotProtocol.digest(request.bytes())) }
            assertTrue(mutated)
        } }
        Files.move(source.resolve("base.bin"), source.resolve("outside.bin"), ATOMIC_MOVE)
        Files.createSymbolicLink(source.resolve("base.bin"), Path.of("outside.bin"))
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            session.hello(cache.toString(), request.expectedCacheIdentity)
            session.reopen(request.snapshotId, request.operation, SnapshotProtocol.digest(request.bytes()))
            assertFails { session.verifySource(request, SnapshotProtocol.digest(request.bytes())) }
        } }
        assertEquals("CHANGED!", Files.readString(source.resolve("outside.bin")))
    }
    @Test fun `source proof checks complete directory membership and final parent bindings`() = fixture { source, cache ->
        val selected = Files.createDirectory(source.resolve("tree")); Files.writeString(selected.resolve("base.bin"), "ORIGINAL")
        lateinit var request: SnapshotCapture
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            val identity = SnapshotProtocol.decode(session.hello(cache.toString(), "")) { SnapshotProtocol.text(this) }
            request = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), source.toString(), cache.toString(), identity,
                listOf(SnapshotSelection("tree", "group/tree")), 1024, 16, 0)
            session.prepare(request, SnapshotProtocol.digest(request.bytes())); session.capture(request, SnapshotProtocol.digest(request.bytes()))
            Files.writeString(selected.resolve("extra.bin"), "NEW MEMBER")
            val proof = SnapshotSourceProof.read(session.verifySource(request, SnapshotProtocol.digest(request.bytes())))
            assertEquals(SnapshotSourceVerification.MISMATCH, proof.result)
            Files.delete(selected.resolve("extra.bin"))
        } }
        var rebound = false
        LinuxDescriptors().use { native -> WorkerSession(native, AcquisitionHooks(afterDirectoryNames = {
            if (!rebound) {
                Files.move(selected, source.resolve("retained-tree"), ATOMIC_MOVE)
                Files.createDirectory(selected); Files.writeString(selected.resolve("base.bin"), "FOREIGN")
                rebound = true
            }
        })).use { session ->
            session.hello(cache.toString(), request.expectedCacheIdentity)
            session.reopen(request.snapshotId, request.operation, SnapshotProtocol.digest(request.bytes()))
            assertFailsWith<IllegalArgumentException> { session.verifySource(request, SnapshotProtocol.digest(request.bytes())) }
            assertTrue(rebound)
        } }
        assertEquals("FOREIGN", Files.readString(selected.resolve("base.bin")))
    }
    @Test fun `source proof refuses an ancestor rebind even when held descendants remain stable`() = fixture { source, cache ->
        val parent = Files.createDirectory(source.resolve("parent"))
        val actual = Files.createDirectory(parent.resolve("actual")); Files.writeString(actual.resolve("base.bin"), "ORIGINAL")
        lateinit var request: SnapshotCapture
        LinuxDescriptors().use { native -> WorkerSession(native).use { session ->
            request = capture(session, actual, cache); session.capture(request, SnapshotProtocol.digest(request.bytes()))
        } }
        var swapped = false
        LinuxDescriptors().use { native -> WorkerSession(native, AcquisitionHooks(afterSourceVerifyRead = {
            if (!swapped) {
                Files.move(parent, source.resolve("retained-parent"), ATOMIC_MOVE)
                Files.createDirectory(parent); Files.createDirectory(actual); Files.writeString(actual.resolve("base.bin"), "FOREIGN")
                swapped = true
            }
        })).use { session ->
            session.hello(cache.toString(), request.expectedCacheIdentity)
            session.reopen(request.snapshotId, request.operation, SnapshotProtocol.digest(request.bytes()))
            val failure = assertFailsWith<IllegalArgumentException> { session.verifySource(request, SnapshotProtocol.digest(request.bytes())) }
            assertTrue(swapped)
            assertEquals("Source root path rebound during proof", failure.message)
        } }
        assertEquals("FOREIGN", Files.readString(actual.resolve("base.bin")))
        assertEquals("ORIGINAL", Files.readString(source.resolve("retained-parent/actual/base.bin")))
    }
}
