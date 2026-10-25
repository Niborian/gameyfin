package org.gameyfin.snapshot

import java.io.Closeable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Pinned Linux x86-64 LP64/glibc UAPI. Never loaded by the Spring/PF4J process.
 * All memory belongs to confined arenas and every OS descriptor has one owner.
 * No sun.nio internals, reflective FileDescriptor mutation, JNI or path fallback.
 */
internal class LinuxDescriptors(private val operationGuard: () -> Unit = {}) : Closeable {
    private val linker: Linker
    private val arena = Arena.ofConfined()
    private val error: MemorySegment
    private val errnoOffset: Long
    private val calls = mutableMapOf<String, MethodHandle>()
    val uid: Int
    internal var liveArgumentArenas = 0
        private set
    internal var closedArgumentArenas = 0L
        private set
    private fun <T> temporary(action: (Arena) -> T): T {
        val local = Arena.ofConfined(); liveArgumentArenas++
        try { return action(local) }
        finally {
            local.close()
            check(!local.scope().isAlive)
            liveArgumentArenas--; closedArgumentArenas++
        }
    }

    init {
        require(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "amd64") { "Unsupported platform" }
        require(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN && ADDRESS.byteSize() == 8L)
        require(javaClass.module.isNativeAccessEnabled) { "Explicit worker native access required" }
        linker = Linker.nativeLinker()
        require(linker.canonicalLayouts()["long"]?.byteSize() == 8L && linker.canonicalLayouts()["size_t"]?.byteSize() == 8L)
        require(linker.defaultLookup().find("gnu_get_libc_version").isPresent) { "Reviewed glibc ABI required" }
        val state = Linker.Option.captureStateLayout()
        error = arena.allocate(state)
        errnoOffset = state.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"))
        uid = call("geteuid", FunctionDescriptor.of(JAVA_INT)).toInt()
    }

    internal class Fd(val value: Int, private val native: LinuxDescriptors) : Closeable {
        private var closed = false
        override fun close() { if (!closed) { closed = true; native.closeFd(value) } }
    }
    data class Stat(val devMajor: Int, val devMinor: Int, val inode: Long, val mode: Int, val uid: Int, val gid: Int,
                    val links: Int, val size: Long, val modified: String, val changed: String) {
        val directory get() = mode and 0xf000 == 0x4000
        val regular get() = mode and 0xf000 == 0x8000
        val objectIdentity get() = "$devMajor:$devMinor:$inode:${mode and 0xf000}:$uid:$gid"
        val contentIdentity get() = "$objectIdentity:$size:$modified:$changed:$links"
    }
    private fun call(name: String, descriptor: FunctionDescriptor, vararg args: Any): Long {
        operationGuard()
        val handle = calls.getOrPut(name) { linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, Linker.Option.captureCallState("errno")) }
        val result = handle.invokeWithArguments(listOf(error) + args.toList()) as Number
        if (result.toLong() == -1L) throw NativeFailure(error.get(JAVA_INT, errnoOffset))
        return result.toLong()
    }
    private fun syscall(number: Long, descriptor: FunctionDescriptor, vararg args: Any): Long {
        operationGuard()
        val key = "syscall-$number"
        val handle = calls.getOrPut(key) { linker.downcallHandle(linker.defaultLookup().find("syscall").orElseThrow(), descriptor,
            Linker.Option.firstVariadicArg(1), Linker.Option.captureCallState("errno")) }
        val result = handle.invokeWithArguments(listOf(error, number) + args.toList()) as Long
        if (result == -1L) throw NativeFailure(error.get(JAVA_INT, errnoOffset))
        return result
    }
    class NativeFailure(val errno: Int) : IllegalStateException("Native operation refused (errno=$errno)")
    private fun c(value: String, storage: Arena): MemorySegment {
        require(value.toByteArray().size <= 4096 && !value.contains('\u0000'))
        return storage.allocateFrom(value)
    }
    fun open(parent: Fd, relative: String, directory: Boolean = false, create: Boolean = false): Fd {
        SnapshotProtocol.relative(relative)
        val flags = if (directory) 0x10000L else if (create) 2L or 0x40L or 0x80L else 0L
        return openRaw(parent.value, relative, flags or 0x80000L or 0x20000L or 0x800L, if (create) 0x180 else 0, 0x0f)
    }
    fun observe(parent: Fd, relative: String): Stat = openRaw(parent.value, SnapshotProtocol.relative(relative), 0x200000L or 0x80000L or 0x20000L, 0, 0x0f).use(::stat)
    private fun openRaw(parent: Int, path: String, flags: Long, mode: Long, resolve: Long): Fd = temporary { local ->
        val how = local.allocate(24, 8).fill(0)
        how.set(JAVA_LONG, 0, flags); how.set(JAVA_LONG, 8, mode); how.set(JAVA_LONG, 16, resolve)
        val fd = syscall(437, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG), parent, c(path, local), how, 24L)
        require(fd in 0..Int.MAX_VALUE.toLong())
        Fd(fd.toInt(), this)
    }
    /** Bootstrap each absolute component beneath its retained predecessor, rejecting symlinks.
     * Mount crossings are allowed only during trusted root bootstrap, never selected traversal.
     */
    fun root(absolute: String, owned: Boolean): Fd {
        require(absolute.startsWith('/') && !absolute.endsWith('/') && absolute != "/")
        val parts = absolute.substring(1).split('/'); parts.forEach { SnapshotProtocol.relative(it); require(!it.contains('/')) }
        var current = openRaw(-100, "/", 0x10000L or 0x80000L or 0x20000L, 0, 0x06)
        try {
            if (owned) trustedDirectory(stat(current), false)
            for (part in parts) {
                val next = openRaw(current.value, part, 0x10000L or 0x80000L or 0x20000L, 0, 0x0e)
                try { if (owned) trustedDirectory(stat(next), false) } catch (failure: Throwable) { next.close(); throw failure }
                current.close(); current = next
            }
            if (owned) trustedDirectory(stat(current), true)
            return current
        } catch (failure: Throwable) { current.close(); throw failure }
    }
    fun trustedDirectory(stat: Stat, serviceOwned: Boolean) {
        require(stat.directory && stat.mode and 0x12 == 0 && (if (serviceOwned) stat.uid == uid else stat.uid == uid || stat.uid == 0)) { "Untrusted cache ancestry" }
    }
    fun stat(fd: Fd): Stat = statAt(fd, "", 0x1000)
    fun statAt(fd: Fd, relative: String, flags: Int = 0x100): Stat = temporary { local ->
        if (relative.isNotEmpty()) SnapshotProtocol.relative(relative)
        // statx UAPI is 256 bytes, aligned to u64, unlike libc struct stat.
        val out = local.allocate(256, 8).fill(0)
        call("statx", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), fd.value, c(relative, local), flags, 0x7ff, out)
        val mask = out.get(JAVA_INT, 0)
        require(mask and 0x7df == 0x7df) { "Filesystem lacks required statx fields" }
        fun timestamp(offset: Long) = "${out.get(JAVA_LONG, offset)}:${out.get(JAVA_INT, offset + 8)}"
        Stat(out.get(JAVA_INT, 136), out.get(JAVA_INT, 140), out.get(JAVA_LONG, 32),
            out.get(JAVA_SHORT, 28).toInt() and 0xffff, out.get(JAVA_INT, 20), out.get(JAVA_INT, 24),
            out.get(JAVA_INT, 16), out.get(JAVA_LONG, 40), timestamp(112), timestamp(96))
    }
    fun directory(parent: Fd, name: String): Fd {
        SnapshotProtocol.relative(name); require(!name.contains('/'))
        temporary { local -> call("mkdirat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), parent.value, c(name, local), 0x1c0) }
        return open(parent, name, directory = true).also { trustedDirectory(stat(it), true) }
    }
    fun names(directory: Fd): List<String> = temporary { local ->
        // getdents64 advances the retained directory's open-file-description offset.
        // Revalidation/retirement must enumerate the whole tree again, not see EOF.
        seek(directory, 0)
        val result = mutableListOf<String>(); val buffer = local.allocate(65536, 8)
        var complete = false
        while (!complete) {
            val size = syscall(217, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT), directory.value, buffer, 65536).toInt()
            if (size == 0) { complete = true; continue }
            require(size in 1..65536)
            var cursor = 0L
            while (cursor < size) {
                require(cursor + 19 <= size)
                val record = buffer.get(JAVA_SHORT, cursor + 16).toInt() and 0xffff
                require(record >= 20 && cursor + record <= size)
                var end = cursor + 19
                while (end < cursor + record && buffer.get(JAVA_BYTE, end) != 0.toByte()) end++
                require(end < cursor + record)
                val encoded = buffer.asSlice(cursor + 19, end - cursor - 19).toArray(JAVA_BYTE)
                val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(encoded)).toString()
                if (name != "." && name != "..") {
                    SnapshotProtocol.relative(name); require(!name.contains('/'))
                    result.add(name); require(result.size <= SnapshotProtocol.MAX_MEMBERS)
                }
                cursor += record
            }
        }
        result
    }
    fun read(fd: Fd, buffer: MemorySegment, count: Int): Int = call("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), fd.value, buffer, count.toLong()).toInt()
    fun write(fd: Fd, bytes: ByteArray) {
        temporary { local ->
            val memory = local.allocate(bytes.size.toLong().coerceAtLeast(1)); MemorySegment.copy(bytes, 0, memory, JAVA_BYTE, 0, bytes.size)
            var offset = 0
            while (offset < bytes.size) {
                val count = call("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), fd.value, memory.asSlice(offset.toLong()), (bytes.size - offset).toLong()).toInt()
                require(count > 0); offset += count
            }
        }
    }
    fun seek(fd: Fd, offset: Long) { require(offset >= 0); call("lseek", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT), fd.value, offset, 0) }
    fun fsync(fd: Fd) { call("fsync", FunctionDescriptor.of(JAVA_INT, JAVA_INT), fd.value) }
    fun mode(fd: Fd, value: Int) { call("fchmod", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), fd.value, value) }
    fun publish(cache: Fd, pending: String, completed: String) {
        require(!pending.contains('/') && !completed.contains('/'))
        temporary { local -> call("renameat2", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), cache.value, c(pending, local), cache.value, c(completed, local), 1) }
        fsync(cache)
    }
    fun unlink(parent: Fd, name: String, directory: Boolean) {
        SnapshotProtocol.relative(name); require(!name.contains('/'))
        temporary { local -> call("unlinkat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
            parent.value, c(name, local), if (directory) 0x200 else 0) }
    }
    fun reserve(cache: Fd, required: Long) = temporary { local ->
        require(required >= 0)
        // Linux x86-64 statfs UAPI: f_bsize@8, f_bavail@32. No ABI fallback.
        val out = local.allocate(120, 8).fill(0)
        call("fstatfs", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS), cache.value, out)
        val block = out.get(JAVA_LONG, 8); val available = out.get(JAVA_LONG, 32)
        require(block > 0 && available >= 0 && available >= (required / block + if (required % block == 0L) 0 else 1)) { "Insufficient storage reserve" }
    }
    fun digest(fd: Fd, expected: Long, consumer: ((ByteArray) -> Unit)? = null): String {
        require(expected >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        temporary { local ->
            val buffer = local.allocate(SnapshotProtocol.BLOCK.toLong()); var total = 0L
            while (true) {
                check(!Thread.currentThread().isInterrupted)
                val count = read(fd, buffer, SnapshotProtocol.BLOCK)
                require(count >= 0)
                if (count == 0) break
                require(count <= expected - total) { "Source grew during acquisition" }
                val bytes = buffer.asSlice(0, count.toLong()).toArray(JAVA_BYTE)
                digest.update(bytes); consumer?.invoke(bytes); total += count
            }
            require(total == expected) { "Source shrank during acquisition" }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun closeFd(fd: Int) {
        // Do not retry close after EINTR: the descriptor may already be closed/reused.
        // Cleanup must remain possible after a deadline/parent-loss guard trips.
        val handle = calls.getOrPut("close") { linker.downcallHandle(linker.defaultLookup().find("close").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT), Linker.Option.captureCallState("errno")) }
        val result = handle.invokeWithArguments(error, fd) as Int
        if (result == -1) throw NativeFailure(error.get(JAVA_INT, errnoOffset))
    }
    override fun close() { arena.close() }
}
