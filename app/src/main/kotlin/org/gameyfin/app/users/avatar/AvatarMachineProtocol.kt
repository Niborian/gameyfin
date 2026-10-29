package org.gameyfin.app.users.avatar

import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Version-one private wire contract. Pins assert expectations, NEVER establish authority. */
internal object AvatarMachineProtocol {
    const val HEADER = "X-Gameyfin-Avatar-Pin"
    private val hash = Regex("[0-9a-f]{64}")
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    fun parse(value: String): JsonNode {
        require(value.length in 1..4096 && value.all { it.code in 32..126 })
        return mapper.readTree(value)
    }
    fun scope(root: JsonNode): AvatarLedger.Scope {
        exact(root, setOf("scope"))
        return scopeValue(root["scope"])
    }
    fun intent(root: JsonNode, action: AvatarLedger.Action): AvatarLedger.Intent {
        exact(root, setOf("scope", "operation", "expectedVersion", "expectedHash", "sourceGeneration", "desiredHash"))
        return AvatarLedger.Intent(scopeValue(root["scope"]), uuid(root, "operation"), action,
            number(root, "expectedVersion", 0), optionalHash(root, "expectedHash"),
            number(root, "sourceGeneration", 1), optionalHash(root, "desiredHash"))
    }
    data class Recovery(val installation: UUID, val operation: UUID, val intentDigest: String, val restore: UUID)
    fun recovery(root: JsonNode): Recovery {
        exact(root, setOf("installation", "operation", "intentDigest", "restore"))
        val digest = text(root, "intentDigest"); require(digest.matches(hash))
        return Recovery(uuid(root, "installation"), uuid(root, "operation"), digest, uuid(root, "restore"))
    }
    fun json(value: Any): ByteArray = mapper.writeValueAsBytes(value)
    fun receipt(value: AvatarLedger.Receipt): Map<String, Any?> = mapOf(
        "scope" to scopeMap(value.scope), "operation" to value.operation.toString(),
        "intentDigest" to value.intentDigest, "action" to value.action.name,
        "resultingVersion" to value.resultingVersion, "resultingHash" to value.resultingHash,
        "sourceGeneration" to value.sourceGeneration, "committedAt" to value.committedAt.toString())

    private fun scopeValue(value: JsonNode): AvatarLedger.Scope {
        exact(value, setOf("installation", "restore", "configuration", "imageDigest", "buildChecksum",
            "grant", "grantGeneration", "bindingDigest", "bindingGeneration", "userId"))
        return AvatarLedger.Scope(uuid(value, "installation"), uuid(value, "restore"), number(value, "configuration", 1),
            text(value, "imageDigest"), text(value, "buildChecksum"), uuid(value, "grant"),
            number(value, "grantGeneration", 1), text(value, "bindingDigest"),
            number(value, "bindingGeneration", 1), number(value, "userId", 1))
    }
    private fun scopeMap(s: AvatarLedger.Scope) = mapOf("installation" to s.installation.toString(),
        "restore" to s.restore.toString(), "configuration" to s.configuration,
        "imageDigest" to s.imageDigest, "buildChecksum" to s.buildChecksum,
        "grant" to s.grant.toString(), "grantGeneration" to s.grantGeneration,
        "bindingDigest" to s.bindingDigest, "bindingGeneration" to s.bindingGeneration, "userId" to s.userId)
    private fun exact(value: JsonNode, keys: Set<String>) {
        require(value.isObject && value.propertyNames().toSet() == keys)
    }
    private fun text(value: JsonNode, name: String): String {
        val node = value[name]; require(node != null && node.isString)
        return node.stringValue()
    }
    private fun number(value: JsonNode, name: String, minimum: Long): Long {
        val node = value[name]; require(node != null && node.isIntegralNumber && node.canConvertToLong())
        return node.longValue().also { require(it >= minimum) }
    }
    private fun uuid(value: JsonNode, name: String): UUID {
        val raw = text(value, name)
        return UUID.fromString(raw).also { require(it.toString() == raw) }
    }
    private fun optionalHash(value: JsonNode, name: String): String? =
        if (value[name].isNull) null else text(value, name).also { require(it.matches(hash)) }
}
