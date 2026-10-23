package org.gameyfin.tools

/** Fixture-only trackerless creator verification; never weakens the application's tracker admission gate. */
internal object CreatorMetadata {
    fun decode(data: ByteArray): Any {
        require(data.size <= 1024 * 1024)
        var offset = 0; var nodes = 0
        fun parse(depth: Int): Any {
            require(depth <= 16 && ++nodes <= 4096 && offset < data.size)
            return when (data[offset].toInt().toChar()) {
                'i' -> {
                    val start = ++offset
                    while (offset < data.size && data[offset] != 'e'.code.toByte()) offset++
                    require(offset < data.size)
                    val raw = data.copyOfRange(start, offset++).toString(Charsets.US_ASCII)
                    require(raw.matches(Regex("0|-?[1-9][0-9]{0,18}"))); requireNotNull(raw.toLongOrNull())
                }
                'l' -> {
                    offset++; val values = mutableListOf<Any>()
                    while (offset < data.size && data[offset] != 'e'.code.toByte()) values.add(parse(depth + 1))
                    require(offset < data.size); offset++; values
                }
                'd' -> {
                    offset++; val values = linkedMapOf<String, Any>()
                    while (offset < data.size && data[offset] != 'e'.code.toByte()) {
                        val key = parse(depth + 1) as? ByteArray ?: error("Fixture dictionary key")
                        require(key.all { it.toInt() in 32..126 }); val name = key.toString(Charsets.US_ASCII)
                        require(!values.containsKey(name)); values[name] = parse(depth + 1)
                    }
                    require(offset < data.size); offset++; values
                }
                else -> {
                    val start = offset
                    while (offset < data.size && data[offset] != ':'.code.toByte()) offset++
                    require(offset < data.size)
                    val raw = data.copyOfRange(start, offset++).toString(Charsets.US_ASCII)
                    require(raw.matches(Regex("0|[1-9][0-9]{0,6}")))
                    val length = raw.toInt(); require(length <= data.size - offset)
                    data.copyOfRange(offset, offset + length).also { offset += length }
                }
            }
        }
        val result = parse(0); require(offset == data.size); return result
    }
    @Suppress("UNCHECKED_CAST")
    fun verify(data: ByteArray, expected: Map<String, ByteArray>, rootName: String) {
        val info = (decode(data) as Map<String, Any>)["info"] as Map<String, Any>
        fun component(value: Any?): String {
            val bytes = value as? ByteArray ?: error("Fixture byte string")
            val name = bytes.toString(Charsets.UTF_8)
            require(name.isNotEmpty() && name !in listOf(".", "..") && '/' !in name && '\\' !in name)
            return name
        }
        require(component(info["name"]) == rootName && info["private"] == 1L)
        val size = info["piece length"] as Long; require(size in 16384..16 * 1024 * 1024)
        val payload = java.io.ByteArrayOutputStream(); val seen = mutableSetOf<String>()
        for (entry in info["files"] as List<Map<String, Any>>) {
            val parts = entry["path"] as List<Any>; require(parts.isNotEmpty())
            val name = parts.joinToString("/") { component(it) }; require(seen.add(name) && expected.containsKey(name))
            val bytes = requireNotNull(expected[name]); require(entry["length"] == bytes.size.toLong()); payload.write(bytes)
        }
        require(seen == expected.keys) { "Selected members missing" }
        val bytes = payload.toByteArray(); val pieces = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < bytes.size) { pieces.write(java.security.MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(offset, minOf(bytes.size, offset + size.toInt())))); offset += size.toInt() }
        require((info["pieces"] as ByteArray).contentEquals(pieces.toByteArray())) { "Torrent pieces differ" }
    }
}
