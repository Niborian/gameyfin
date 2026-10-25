package org.gameyfin.snapshot

import java.io.*
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Binary, length-framed and bounded. No Java serialization, shell or credentials. */
object SnapshotProtocol {
    const val VERSION = 1
    const val MAX_FRAME = 1024 * 1024
    const val MAX_MEMBERS = 4096
    const val BLOCK = 64 * 1024
    const val CAPTURE = 1
    const val OPEN = 2
    const val READ = 3
    const val CLOSE = 4
    const val HELLO = 5
    const val VERIFY = 6
    const val PREPARE = 7
    const val RETIRE = 8

    fun frame(input: InputStream): ByteArray {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        require(size in 1..MAX_FRAME) { "Invalid frame length" }
        return ByteArray(size).also(stream::readFully)
    }
    fun frame(output: OutputStream, bytes: ByteArray) {
        require(bytes.size in 1..MAX_FRAME)
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    fun encode(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
    fun <T> decode(bytes: ByteArray, read: DataInputStream.() -> T): T = DataInputStream(ByteArrayInputStream(bytes)).use {
        val result = read.invoke(it); require(it.available() == 0) { "Trailing protocol bytes" }; result
    }
    fun text(input: DataInputStream, limit: Int = 4096): String {
        val size = input.readInt(); require(size in 0..limit)
        val bytes = ByteArray(size).also(input::readFully)
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    fun text(output: DataOutputStream, value: String, limit: Int = 4096) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8); require(bytes.size <= limit)
        output.writeInt(bytes.size); output.write(bytes)
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun relative(value: String): String {
        require(value.isNotEmpty() && value.toByteArray().size <= 4096 && !value.contains('\\') && !value.contains('\u0000'))
        val parts = value.split('/')
        require(parts.size in 1..32 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) })
        return value
    }
    fun distinctNames(names: List<String>) {
        require(names.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == names.size)
        val prefixes = mutableMapOf<String, String>()
        for (name in names) {
            relative(name)
            val parts = name.split('/')
            for (length in 1..parts.size) {
                val prefix = parts.take(length).joinToString("/")
                val old = prefixes.putIfAbsent(prefix.lowercase(java.util.Locale.ROOT), prefix)
                require(old == null || old == prefix) { "Case-colliding path components" }
            }
        }
    }
}

data class SnapshotSelection(val sourceRelative: String, val generatedRelative: String) {
    init { SnapshotProtocol.relative(sourceRelative); SnapshotProtocol.relative(generatedRelative) }
}

/** Absolute roots are host configuration, not a browser/plugin request capability. */
data class SnapshotCapture(
    val snapshotId: UUID, val operation: UUID, val sourceRoot: String, val cacheRoot: String,
    val expectedCacheIdentity: String, val selections: List<SnapshotSelection>,
    val byteLimit: Long, val fileLimit: Int, val reserveBytes: Long,
) {
    init {
        require(sourceRoot.startsWith('/') && cacheRoot.startsWith('/'))
        require(sourceRoot != cacheRoot && !sourceRoot.startsWith(cacheRoot.trimEnd('/') + "/") && !cacheRoot.startsWith(sourceRoot.trimEnd('/') + "/")) { "Source and cache roots must be disjoint" }
        require(byteLimit in 1..1024L * 1024 * 1024 * 1024 && fileLimit in 1..SnapshotProtocol.MAX_MEMBERS && reserveBytes >= 0)
        require(selections.size in 1..fileLimit)
        SnapshotProtocol.distinctNames(selections.map { it.generatedRelative })
        require(selections.none { a -> selections.any { b -> a !== b &&
            (a.generatedRelative.lowercase(java.util.Locale.ROOT).startsWith(b.generatedRelative.lowercase(java.util.Locale.ROOT) + "/") ||
                b.generatedRelative.lowercase(java.util.Locale.ROOT).startsWith(a.generatedRelative.lowercase(java.util.Locale.ROOT) + "/")) } })
        require(selections.none { a -> selections.any { b -> a !== b && (a.sourceRelative == b.sourceRelative || a.sourceRelative.startsWith(b.sourceRelative + "/") || b.sourceRelative.startsWith(a.sourceRelative + "/")) } })
    }
    fun bytes(command: Int = SnapshotProtocol.CAPTURE): ByteArray = SnapshotProtocol.encode {
        require(command == SnapshotProtocol.CAPTURE || command == SnapshotProtocol.PREPARE)
        writeInt(SnapshotProtocol.VERSION); writeInt(command)
        SnapshotProtocol.text(this, snapshotId.toString()); SnapshotProtocol.text(this, operation.toString())
        SnapshotProtocol.text(this, sourceRoot); SnapshotProtocol.text(this, cacheRoot)
        SnapshotProtocol.text(this, expectedCacheIdentity); writeLong(byteLimit); writeInt(fileLimit); writeLong(reserveBytes)
        writeInt(selections.size)
        selections.forEach { SnapshotProtocol.text(this, it.sourceRelative); SnapshotProtocol.text(this, it.generatedRelative) }
    }
    companion object {
        fun read(input: DataInputStream): SnapshotCapture {
            val id = UUID.fromString(SnapshotProtocol.text(input)); val operation = UUID.fromString(SnapshotProtocol.text(input))
            val source = SnapshotProtocol.text(input); val cache = SnapshotProtocol.text(input); val identity = SnapshotProtocol.text(input)
            val bytes = input.readLong(); val count = input.readInt(); val reserve = input.readLong()
            val selected = input.readInt(); require(selected in 1..SnapshotProtocol.MAX_MEMBERS)
            return SnapshotCapture(id, operation, source, cache, identity, List(selected) { SnapshotSelection(SnapshotProtocol.text(input), SnapshotProtocol.text(input)) }, bytes, count, reserve)
        }
    }
}

data class SnapshotPreparation(val snapshotId: UUID, val operation: UUID, val requestDigest: String,
    val cacheIdentity: String, val directoryIdentity: String, val dataIdentity: String) {
    fun bytes(): ByteArray = SnapshotProtocol.encode {
        writeInt(SnapshotProtocol.VERSION)
        listOf(snapshotId.toString(), operation.toString(), requestDigest, cacheIdentity, directoryIdentity, dataIdentity).forEach { SnapshotProtocol.text(this, it) }
    }
    companion object {
        fun read(bytes: ByteArray): SnapshotPreparation = SnapshotProtocol.decode(bytes) {
            require(readInt() == SnapshotProtocol.VERSION)
            SnapshotPreparation(UUID.fromString(SnapshotProtocol.text(this)), UUID.fromString(SnapshotProtocol.text(this)),
                SnapshotProtocol.text(this), SnapshotProtocol.text(this), SnapshotProtocol.text(this), SnapshotProtocol.text(this)).also {
                require(it.requestDigest.matches(Regex("[0-9a-f]{64}")) && listOf(it.cacheIdentity, it.directoryIdentity, it.dataIdentity).all { value -> value.isNotBlank() && value.length <= 512 })
            }
        }
    }
}

data class SnapshotMember(val name: String, val bytes: Long, val digest: String, val identity: String, val sourceIdentity: String)
data class SnapshotManifest(val snapshotId: UUID, val operation: UUID, val requestDigest: String, val cacheIdentity: String, val directoryIdentity: String, val members: List<SnapshotMember>) {
    fun bytes(): ByteArray = SnapshotProtocol.encode {
        writeInt(SnapshotProtocol.VERSION)
        listOf(snapshotId.toString(), operation.toString(), requestDigest, cacheIdentity, directoryIdentity).forEach { SnapshotProtocol.text(this, it) }
        writeInt(members.size)
        members.forEach { SnapshotProtocol.text(this, it.name); writeLong(it.bytes); SnapshotProtocol.text(this, it.digest); SnapshotProtocol.text(this, it.identity); SnapshotProtocol.text(this, it.sourceIdentity) }
    }
    companion object {
        fun read(bytes: ByteArray): SnapshotManifest = SnapshotProtocol.decode(bytes) {
            require(readInt() == SnapshotProtocol.VERSION)
            val id = UUID.fromString(SnapshotProtocol.text(this)); val op = UUID.fromString(SnapshotProtocol.text(this))
            val request = SnapshotProtocol.text(this); val cache = SnapshotProtocol.text(this); val directory = SnapshotProtocol.text(this)
            val count = readInt(); require(count in 1..SnapshotProtocol.MAX_MEMBERS)
            val members = List(count) { SnapshotMember(SnapshotProtocol.relative(SnapshotProtocol.text(this)), readLong().also { require(it >= 0) }, SnapshotProtocol.text(this), SnapshotProtocol.text(this), SnapshotProtocol.text(this)) }
            require(members.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet().size == count)
            SnapshotProtocol.distinctNames(members.map { it.name })
            require(request.matches(Regex("[0-9a-f]{64}")) && members.all { it.digest.matches(Regex("[0-9a-f]{64}")) })
            SnapshotManifest(id, op, request, cache, directory, members)
        }
    }
}
