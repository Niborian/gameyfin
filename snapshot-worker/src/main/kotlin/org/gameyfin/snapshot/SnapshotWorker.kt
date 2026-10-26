package org.gameyfin.snapshot

import java.io.Closeable
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.UUID

/** One supervised private worker owns cache descriptors for acquisition AND consumption.
 * No socket, HTTP endpoint, plugin loader or qB credentials. Failure retains owned state
 * for explicit identity-checked reconciliation; it never recursively removes a guessed path.
 */
object SnapshotWorker {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 2)
            val parent = ProcessHandle.of(args[0].toLong()).orElseThrow()
            require(ProcessHandle.current().parent().orElseThrow().pid() == parent.pid() && parent.isAlive)
            val maximumMillis = args[1].toLong().also { require(it in 1..3_600_000) }
            var deadline = Long.MAX_VALUE
            val guard = {
                check(parent.isAlive && System.nanoTime() <= deadline) { "Worker owner lost or deadline exceeded" }
            }
            LinuxDescriptors(guard).use { native ->
                WorkerSession(native).use { session ->
                    while (true) {
                        val request = SnapshotProtocol.frame(System.`in`)
                        deadline = System.nanoTime() + maximumMillis * 1_000_000
                        guard()
                        var close = false
                        val response = SnapshotProtocol.decode(request) {
                            require(readInt() == SnapshotProtocol.VERSION)
                            when (readInt()) {
                                SnapshotProtocol.HELLO -> session.hello(SnapshotProtocol.text(this), SnapshotProtocol.text(this))
                                SnapshotProtocol.CAPTURE -> session.capture(SnapshotCapture.read(this), SnapshotProtocol.digest(request))
                                SnapshotProtocol.PREPARE -> {
                                    val selected = SnapshotCapture.read(this)
                                    session.prepare(selected, SnapshotProtocol.digest(selected.bytes()))
                                }
                                SnapshotProtocol.OPEN -> session.reopen(UUID.fromString(SnapshotProtocol.text(this)), UUID.fromString(SnapshotProtocol.text(this)), SnapshotProtocol.text(this))
                                SnapshotProtocol.READ -> session.read(SnapshotProtocol.text(this), readLong(), readInt())
                                SnapshotProtocol.VERIFY -> session.verify()
                                SnapshotProtocol.VERIFY_SOURCE -> {
                                    val current = SnapshotCapture.read(this)
                                    session.verifySource(current, SnapshotProtocol.digest(current.bytes()))
                                }
                                SnapshotProtocol.RETIRE -> {
                                    val length = readInt(); require(length in 1..SnapshotProtocol.MAX_FRAME)
                                    val preparation = ByteArray(length).also(::readFully)
                                    session.retire(SnapshotPreparation.read(preparation))
                                }
                                SnapshotProtocol.CLOSE -> { close = true; byteArrayOf() }
                                else -> error("Unsupported operation")
                            }
                        }
                        SnapshotProtocol.frame(System.out, byteArrayOf(0) + response)
                        if (close) return
                    }
                }
            }
        } catch (_: Throwable) {
            // Do not print source paths, native buffers or protocol data to ordinary logs.
            runCatching { SnapshotProtocol.frame(System.out, byteArrayOf(1)) }
            kotlin.system.exitProcess(1)
        }
    }
}

internal data class AcquisitionHooks(val beforeSourceOpen: () -> Unit = {}, val afterSourceOpen: () -> Unit = {},
    val afterWrite: () -> Unit = {}, val beforePublication: () -> Unit = {}, val afterDirectoryNames: () -> Unit = {},
    val afterSourceVerifyRead: () -> Unit = {})

internal class WorkerSession(private val native: LinuxDescriptors, private val hooks: AcquisitionHooks = AcquisitionHooks()) : Closeable {
    private var cache: LinuxDescriptors.Fd? = null
    private var cachePath: String? = null
    private var cacheIdentity: String? = null
    private var snapshot: LinuxDescriptors.Fd? = null
    private var data: LinuxDescriptors.Fd? = null
    private var manifest: SnapshotManifest? = null
    private var preparation: SnapshotPreparation? = null
    private var preparedRoot: LinuxDescriptors.Fd? = null
    private var preparedData: LinuxDescriptors.Fd? = null

    fun hello(path: String, expected: String): ByteArray {
        check(cache == null)
        val opened = native.root(path, owned = true)
        try {
            val identity = native.stat(opened).objectIdentity
            require(expected.isEmpty() || expected == identity) { "Cache root changed" }
            cache = opened; cachePath = path; cacheIdentity = identity
            // Force descriptor statx + openat2 capability now, not based on kernel version.
            native.open(opened, ".capability-probe-does-not-exist", create = false).close()
            error("Unexpected cache probe entry")
        } catch (failure: LinuxDescriptors.NativeFailure) {
            // ENOENT is the only expected result. EPERM/ENOSYS/unsupported flags fail closed.
            if (failure.errno != 2) { opened.close(); cache = null; throw failure }
        }
        return SnapshotProtocol.encode { SnapshotProtocol.text(this, requireNotNull(cacheIdentity)) }
    }
    private fun checkedCache(): LinuxDescriptors.Fd {
        val current = requireNotNull(cache)
        require(native.stat(current).objectIdentity == cacheIdentity)
        native.root(requireNotNull(cachePath), owned = true).use { require(native.stat(it).objectIdentity == cacheIdentity) { "Cache root rebound" } }
        return current
    }
    /** Return exact opened directory identities BEFORE any source read. The host must
     * commit them before sending CAPTURE. An unknown PREPARE may leave an empty owned
     * directory, never a partially copied large source tree without durable identity.
     */
    fun prepare(request: SnapshotCapture, requestDigest: String): ByteArray {
        check(snapshot == null && preparation == null)
        val cache = checkedCache()
        require(request.cacheRoot == cachePath && request.expectedCacheIdentity == cacheIdentity)
        native.reserve(cache, request.reserveBytes)
        val root = native.directory(cache, "pending-${request.operation}")
        try {
            val payload = native.directory(root, "data")
            preparedRoot = root; preparedData = payload
            native.fsync(payload); native.fsync(root); native.fsync(cache)
            val created = SnapshotPreparation(request.snapshotId, request.operation, requestDigest, requireNotNull(cacheIdentity), native.stat(root).objectIdentity, native.stat(payload).objectIdentity)
            preparation = created
            return created.bytes()
        } catch (failure: Throwable) { root.close(); throw failure }
    }
    fun capture(request: SnapshotCapture, requestDigest: String): ByteArray {
        check(snapshot == null)
        val cache = checkedCache()
        val prepared = requireNotNull(preparation)
        require(prepared.snapshotId == request.snapshotId && prepared.operation == request.operation && prepared.requestDigest == requestDigest)
        require(request.cacheRoot == cachePath && request.expectedCacheIdentity == cacheIdentity)
        val pendingName = "pending-${request.operation}"
        val completedName = request.snapshotId.toString()
        native.reserve(cache, request.reserveBytes)
        val root = requireNotNull(preparedRoot)
        val payload = requireNotNull(preparedData)
        val generatedDirectories = linkedMapOf<String, LinuxDescriptors.Fd>()
        try {
            val rootIdentity = native.stat(root).objectIdentity
            require(native.stat(root).objectIdentity == prepared.directoryIdentity)
            require(native.stat(payload).objectIdentity == prepared.dataIdentity)
            val members = mutableListOf<SnapshotMember>()
            val identities = mutableSetOf<String>()
            val generated = mutableSetOf<String>()
            var total = 0L
            var entries = 0
            var manifestBudget = 4096
            native.root(request.sourceRoot, owned = false).use { source ->
                require(native.stat(source).objectIdentity != cacheIdentity) { "Source and cache alias" }
                fun copy(sourceParent: LinuxDescriptors.Fd, relative: String, target: String, destination: LinuxDescriptors.Fd, leaf: String, depth: Int) {
                    require(depth <= 32 && ++entries <= request.fileLimit) { "Snapshot entry quota exceeded" }
                    val before = native.observe(sourceParent, relative)
                    if (before.directory) {
                        native.open(sourceParent, relative, directory = true).use { directory ->
                            require(native.stat(directory).contentIdentity == before.contentIdentity) { "Source directory changed" }
                            native.directory(destination, leaf).use { child ->
                                val names = native.names(directory).sorted()
                                require(names.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == names.size) { "Case-colliding source entries" }
                                hooks.afterDirectoryNames()
                                // Descendants stay relative to this validated parent FD,
                                // never re-traverse a replaceable source-root pathname.
                                for (name in names) copy(directory, name, "$target/$name", child, name, depth + 1)
                                require(native.stat(directory).contentIdentity == before.contentIdentity) { "Directory membership changed" }
                                native.mode(child, 0x140); native.fsync(child)
                            }
                        }
                        return
                    }
                    require(before.regular && before.links >= 1 && before.size >= 0) { "Unsupported source type" }
                    require(identities.add(before.objectIdentity) && generated.add(target.lowercase(java.util.Locale.ROOT))) { "Duplicate source or name" }
                    require(members.size < request.fileLimit && before.size <= request.byteLimit - total) { "Snapshot quota exceeded" }
                    manifestBudget = Math.addExact(manifestBudget, target.toByteArray().size + 1024)
                    require(manifestBudget <= SnapshotProtocol.MAX_FRAME - 4096) { "Manifest budget exceeded" }
                    native.reserve(cache, Math.addExact(request.reserveBytes, before.size))
                    hooks.beforeSourceOpen()
                    native.open(sourceParent, relative).use { input ->
                        hooks.afterSourceOpen()
                        require(native.stat(input).contentIdentity == before.contentIdentity) { "Opened source identity changed" }
                        native.open(destination, leaf, create = true).use { output ->
                            val digest = native.digest(input, before.size) { bytes -> native.write(output, bytes); hooks.afterWrite() }
                            require(native.stat(input).contentIdentity == before.contentIdentity)
                            native.seek(input, 0)
                            require(native.digest(input, before.size) == digest && native.stat(input).contentIdentity == before.contentIdentity) { "Source changed during copy" }
                            native.seek(output, 0)
                            require(native.digest(output, before.size) == digest) { "Copied bytes do not match source" }
                            native.mode(output, 0x100); native.fsync(output)
                            val copied = native.stat(output)
                            require(copied.regular && copied.links == 1 && copied.uid == native.uid && copied.size == before.size)
                            members.add(SnapshotMember(target, copied.size, digest, copied.contentIdentity, before.contentIdentity))
                            total += copied.size
                        }
                    }
                }
                // Generated top-level names are host-produced, not interpreted display text.
                for (selection in request.selections) {
                    val components = selection.generatedRelative.split('/')
                    var parent = payload
                    var prefix = ""
                    for (component in components.dropLast(1)) {
                        prefix = if (prefix.isEmpty()) component else "$prefix/$component"
                        val existing = generatedDirectories[prefix]
                        if (existing != null) parent = existing
                        else {
                            require(++entries <= request.fileLimit) { "Generated directory quota exceeded" }
                            val opened = native.directory(parent, component)
                            generatedDirectories[prefix] = opened; parent = opened
                        }
                    }
                    copy(source, selection.sourceRelative, selection.generatedRelative, parent, components.last(), 1)
                }
            }
            require(members.isNotEmpty()) { "No torrent members" }
            generatedDirectories.values.toList().asReversed().forEach { native.mode(it, 0x140); native.fsync(it); it.close() }
            generatedDirectories.clear()
            native.mode(payload, 0x140); native.fsync(payload)
            val result = SnapshotManifest(request.snapshotId, request.operation, requestDigest, requireNotNull(cacheIdentity), rootIdentity, members)
            val encoded = result.bytes(); require(encoded.size < SnapshotProtocol.MAX_FRAME - 1)
            native.open(root, "manifest.bin", create = true).use { file -> native.write(file, encoded); native.mode(file, 0x100); native.fsync(file) }
            native.mode(root, 0x140); native.fsync(root)
            hooks.beforePublication()
            require(native.observe(cache, pendingName).objectIdentity == rootIdentity) { "Pending name rebound" }
            checkedCache()
            native.publish(cache, pendingName, completedName)
            snapshot = root; data = payload; manifest = result
            preparedRoot = null; preparedData = null
            return encoded
        } catch (failure: Throwable) {
            generatedDirectories.values.forEach { runCatching { it.close() } }
            payload?.close(); root.close()
            preparedRoot = null; preparedData = null
            // No unsafe path cleanup. Host marks uncertain; quotas prevent blind retry.
            throw failure
        }
    }
    /** Only host-persisted exact directory identity + no external/descriptor leases may
     * authorize this command. It never opens sources and never follows links/mounts.
     * A partial deletion can be retried against this SAME identity, not a guessed tree.
     */
    fun retire(expected: SnapshotPreparation): ByteArray {
        check(snapshot == null && preparation == null)
        val cache = checkedCache(); require(expected.cacheIdentity == cacheIdentity)
        val names = listOf(expected.snapshotId.toString(), "pending-${expected.operation}", "retiring-${expected.operation}")
        val existing = names.mapNotNull { name ->
            try { name to native.observe(cache, name) }
            catch (failure: LinuxDescriptors.NativeFailure) { if (failure.errno == 2) null else throw failure }
        }
        require(existing.size <= 1) { "Ambiguous owned cache identity" }
        if (existing.isEmpty()) return byteArrayOf()
        val (name, observed) = existing.single()
        require(observed.objectIdentity == expected.directoryIdentity && observed.directory && observed.uid == native.uid)
        native.open(cache, name, directory = true).use { root ->
            require(native.stat(root).objectIdentity == expected.directoryIdentity)
            val entries = native.names(root)
            require(entries.all { it in setOf("data", "manifest.bin") }) { "Unknown operation-root entry" }
            if ("data" in entries) require(native.observe(root, "data").objectIdentity == expected.dataIdentity)
            val retirementName = "retiring-${expected.operation}"
            if (name != retirementName) native.publish(cache, name, retirementName)
            var visited = 0
            fun clear(directory: LinuxDescriptors.Fd, depth: Int) {
                require(depth <= 33)
                native.trustedDirectory(native.stat(directory), true)
                native.mode(directory, 0x1c0)
                for (child in native.names(directory)) {
                    require(++visited <= SnapshotProtocol.MAX_MEMBERS + 4) { "Cleanup entry bound exceeded" }
                    val before = native.observe(directory, child)
                    require(before.uid == native.uid && before.mode and 0x12 == 0)
                    if (before.directory) native.open(directory, child, directory = true).use { opened ->
                        require(native.stat(opened).objectIdentity == before.objectIdentity)
                        clear(opened, depth + 1)
                        require(native.observe(directory, child).objectIdentity == before.objectIdentity)
                        native.unlink(directory, child, true)
                    } else {
                        require(before.regular && before.links == 1) { "Foreign or linked cache entry" }
                        native.open(directory, child).use { opened -> require(native.stat(opened).contentIdentity == before.contentIdentity) }
                        require(native.observe(directory, child).contentIdentity == before.contentIdentity)
                        native.unlink(directory, child, false)
                    }
                }
                native.fsync(directory)
            }
            clear(root, 0)
            require(native.observe(cache, retirementName).objectIdentity == expected.directoryIdentity)
            native.unlink(cache, retirementName, true); native.fsync(cache)
        }
        return byteArrayOf()
    }
    fun reopen(id: UUID, operation: UUID, expectedRequest: String): ByteArray {
        check(snapshot == null)
        require(expectedRequest.matches(Regex("[0-9a-f]{64}")))
        val cache = checkedCache()
        val root = native.open(cache, id.toString(), directory = true)
        var payload: LinuxDescriptors.Fd? = null
        try {
            immutableDirectory(root)
            require(native.names(root).toSet() == setOf("data", "manifest.bin"))
            val encoded = native.open(root, "manifest.bin").use { fd ->
                val stat = native.stat(fd); immutableFile(stat); require(stat.size in 1 until SnapshotProtocol.MAX_FRAME.toLong())
                val output = java.io.ByteArrayOutputStream()
                native.digest(fd, stat.size) { output.write(it) }
                require(native.stat(fd).contentIdentity == stat.contentIdentity)
                output.toByteArray()
            }
            val decoded = SnapshotManifest.read(encoded)
            require(decoded.snapshotId == id && decoded.operation == operation && decoded.requestDigest == expectedRequest && decoded.cacheIdentity == cacheIdentity)
            require(native.stat(root).objectIdentity == decoded.directoryIdentity)
            payload = native.open(root, "data", directory = true)
            val actual = mutableSetOf<String>()
            fun verify(directory: LinuxDescriptors.Fd, prefix: String, depth: Int) {
                require(depth <= 32); immutableDirectory(directory)
                for (name in native.names(directory)) {
                    val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                    val observed = native.observe(directory, name)
                    if (observed.directory) native.open(directory, name, directory = true).use { verify(it, relative, depth + 1) }
                    else {
                        val expected = decoded.members.firstOrNull { it.name == relative } ?: error("Unexpected cached member")
                        require(actual.add(relative)); immutableFile(observed)
                        native.open(directory, name).use { file ->
                            require(native.stat(file).contentIdentity == expected.identity && observed.contentIdentity == expected.identity)
                            require(native.digest(file, expected.bytes) == expected.digest && native.stat(file).contentIdentity == expected.identity)
                        }
                    }
                }
            }
            verify(payload, "", 0)
            require(actual == decoded.members.map { it.name }.toSet())
            snapshot = root; data = payload; manifest = decoded
            return encoded
        } catch (failure: Throwable) { payload?.close(); root.close(); throw failure }
    }
    fun read(name: String, offset: Long, count: Int): ByteArray {
        checkedCache()
        require(count in 1..SnapshotProtocol.BLOCK && offset >= 0)
        val selected = requireNotNull(manifest).members.firstOrNull { it.name == name } ?: error("Not a snapshot member")
        require(offset <= selected.bytes)
        native.open(requireNotNull(data), name).use { fd ->
            require(native.stat(fd).contentIdentity == selected.identity)
            native.seek(fd, offset)
            Arena.ofConfined().use { arena ->
                val length = minOf(count.toLong(), selected.bytes - offset).toInt()
                val buffer = arena.allocate(count.toLong())
                val read = native.read(fd, buffer, length)
                require(read in 0..length && (length == 0 || read > 0))
                require(native.stat(fd).contentIdentity == selected.identity)
                return buffer.asSlice(0, read.toLong()).toArray(ValueLayout.JAVA_BYTE)
            }
        }
    }
    /** Read-only, descriptor-anchored proof of CURRENT source generation. All held
     * source descriptors and their parent bindings are checked again after the
     * complete traversal. An unstable/unsafe/unknown traversal is NOT a mismatch.
     * This does not claim an atomic filesystem snapshot against privileged writers.
     */
    fun verifySource(request: SnapshotCapture, requestDigest: String): ByteArray {
        val expected = requireNotNull(manifest)
        require(request.snapshotId == expected.snapshotId && request.operation == expected.operation)
        require(request.cacheRoot == cachePath && request.expectedCacheIdentity == expected.cacheIdentity)
        verify() // held cache ownership/identity; no COPY or cache metadata write
        data class Held(val fd: LinuxDescriptors.Fd, val parent: LinuxDescriptors.Fd, val relative: String,
            val before: LinuxDescriptors.Stat, val names: List<String>?)
        val held = mutableListOf<Held>()
        val seen = mutableSetOf<String>()
        val sourceIdentities = mutableSetOf<String>()
        val old = expected.members.associateBy { it.name }
        var mismatch = requestDigest != expected.requestDigest
        var contentMismatch = false
        val provenance = java.security.MessageDigest.getInstance("SHA-256")
        fun receipt(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            provenance.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.size).array()); provenance.update(encoded)
        }
        receipt("source-proof-v1"); receipt(requestDigest)
        var total = 0L
        var entries = 0
        try {
            native.root(request.sourceRoot, owned = false).use { source ->
                val sourceBefore = native.stat(source)
                receipt(sourceBefore.contentIdentity)
                fun walk(parent: LinuxDescriptors.Fd, relative: String, target: String, depth: Int) {
                    require(depth <= 32 && ++entries <= request.fileLimit) { "Source proof entry quota exceeded" }
                    val before = native.observe(parent, relative)
                    require(before.directory || (before.regular && before.links >= 1 && before.size >= 0)) { "Unsafe source proof type" }
                    hooks.beforeSourceOpen()
                    val fd = native.open(parent, relative, directory = before.directory)
                    try {
                        hooks.afterSourceOpen()
                        require(native.stat(fd).contentIdentity == before.contentIdentity) { "Source proof open race" }
                        if (before.directory) {
                            val names = native.names(fd).sorted()
                            require(names.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == names.size)
                            held += Held(fd, parent, relative, before, names)
                            receipt("directory"); receipt(target); receipt(before.contentIdentity)
                            receipt(names.size.toString())
                            names.forEach(::receipt)
                            hooks.afterDirectoryNames()
                            for (name in names) walk(fd, name, "$target/$name", depth + 1)
                        } else {
                            require(seen.add(target) && sourceIdentities.add(before.objectIdentity)) { "Duplicate source proof identity or name" }
                            require(before.size <= request.byteLimit - total) { "Source proof byte quota exceeded" }
                            held += Held(fd, parent, relative, before, null)
                            val digest = native.digest(fd, before.size) { hooks.afterSourceVerifyRead() }
                            require(native.stat(fd).contentIdentity == before.contentIdentity) { "Source proof changed during read" }
                            native.seek(fd, 0)
                            require(native.digest(fd, before.size) == digest && native.stat(fd).contentIdentity == before.contentIdentity) { "Source proof unstable bytes" }
                            val prior = old[target]
                            if (prior == null || prior.bytes != before.size || prior.digest != digest) { mismatch = true; contentMismatch = true }
                            if (prior == null || prior.sourceIdentity != before.contentIdentity) mismatch = true
                            receipt("file"); receipt(target); receipt(before.size.toString()); receipt(before.contentIdentity); receipt(digest)
                            total += before.size
                        }
                    } catch (failure: Throwable) {
                        if (held.none { it.fd === fd }) runCatching { fd.close() }
                        throw failure
                    }
                }
                for (selection in request.selections) walk(source, selection.sourceRelative, selection.generatedRelative, 1)
                require(held.size <= request.fileLimit)
                for (item in held) {
                    require(native.stat(item.fd).contentIdentity == item.before.contentIdentity &&
                        native.observe(item.parent, item.relative).contentIdentity == item.before.contentIdentity) { "Source proof membership or identity changed" }
                    if (item.names != null) require(native.names(item.fd).sorted() == item.names) { "Source proof directory membership changed" }
                }
                require(native.stat(source).contentIdentity == sourceBefore.contentIdentity) { "Source root changed during proof" }
                native.root(request.sourceRoot, owned = false).use { currentRoot ->
                    require(native.stat(currentRoot).contentIdentity == sourceBefore.contentIdentity) { "Source root path rebound during proof" }
                }
                if (seen != old.keys) { mismatch = true; contentMismatch = true }
            }
            verify() // the cache binding must still match after the source proof
            val result = if (contentMismatch) SnapshotSourceVerification.MISMATCH else if (mismatch)
                SnapshotSourceVerification.SAME_CONTENT_DIFFERENT_SOURCE else SnapshotSourceVerification.MATCH
            return SnapshotSourceProof(result, SnapshotProtocol.digest(expected.bytes()),
                provenance.digest().joinToString("") { "%02x".format(it) }).bytes()
        } finally {
            var closeFailure: Throwable? = null
            for (item in held.asReversed()) runCatching { item.fd.close() }.exceptionOrNull()?.let {
                if (closeFailure == null) closeFailure = it else closeFailure!!.addSuppressed(it)
            }
            // A close failure is unknown, never permission to admit another generation.
            closeFailure?.let { throw it }
        }
    }
    fun verify(): ByteArray {
        val cache = checkedCache()
        val expected = requireNotNull(manifest)
        val root = requireNotNull(snapshot)
        val payload = requireNotNull(data)
        require(native.observe(cache, expected.snapshotId.toString()).objectIdentity == expected.directoryIdentity)
        require(native.stat(root).objectIdentity == expected.directoryIdentity)
        immutableDirectory(root); immutableDirectory(payload)
        require(native.observe(root, "data").objectIdentity == native.stat(payload).objectIdentity)
        native.open(root, "manifest.bin").use { file ->
            val before = native.stat(file); immutableFile(before)
            val bytes = expected.bytes()
            require(before.size == bytes.size.toLong())
            require(native.digest(file, before.size) == SnapshotProtocol.digest(bytes))
            require(native.stat(file).contentIdentity == before.contentIdentity)
        }
        for (member in expected.members) native.open(payload, member.name).use { file ->
            val stat = native.stat(file); immutableFile(stat)
            require(stat.contentIdentity == member.identity)
        }
        return expected.bytes()
    }
    private fun immutableDirectory(fd: LinuxDescriptors.Fd) { val s = native.stat(fd); require(s.directory && s.uid == native.uid && s.mode and 0x92 == 0) }
    private fun immutableFile(s: LinuxDescriptors.Stat) { require(s.regular && s.uid == native.uid && s.links == 1 && s.mode and 0x92 == 0) }
    override fun close() {
        var primary: Throwable? = null
        for (owned in listOf(data, snapshot, preparedData, preparedRoot, cache)) {
            try { owned?.close() } catch (failure: Throwable) { if (primary == null) primary = failure else primary.addSuppressed(failure) }
        }
        if (primary != null) throw primary
    }
}
