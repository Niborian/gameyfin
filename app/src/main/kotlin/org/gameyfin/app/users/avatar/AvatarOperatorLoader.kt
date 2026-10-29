package org.gameyfin.app.users.avatar

import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Trusted local operator entry only, nonregistered. No defaults, credential,
 * HTTP self-provisioning, watcher or startup activation. Main must independently
 * retain the epoch/custody outside app DB backups and pause dispatch during reload.
 */
class AvatarOperatorLoader(private val files: AvatarOperatorFiles, private val runtime: RuntimeIdentity) {
    class RuntimeIdentity private constructor(val installation: UUID, val imageDigest: String,
        val buildChecksum: String, val bootNonce: UUID) {
        companion object {
            /** Only trusted native bootstrap supplies inventory-verified image/build.
             * Nonce is always fresh in memory, NEVER loaded from an operator file. */
            fun start(installation: UUID, imageDigest: String, buildChecksum: String): RuntimeIdentity {
                require(imageDigest.matches(Regex("sha256:[0-9a-f]{64}")) && buildChecksum.matches(HASH))
                return RuntimeIdentity(installation, imageDigest, buildChecksum, UUID.randomUUID())
            }
        }
        override fun toString() = "AvatarRuntimeIdentity[redacted]"
    }
    internal data class Descriptor(val installation: UUID, val restore: UUID, val configuration: Long,
        val imageDigest: String, val buildChecksum: String, val bootNonce: UUID) {
        override fun toString() = "AvatarOperatorDescriptor[redacted]"
    }
    class Loaded internal constructor(val authority: AvatarAuthority.TrustedConfiguration,
        val accounts: NativeAvatarAccountReader, internal val descriptor: Descriptor,
        internal val hash: String, internal val grant: UUID, internal val generation: Long,
        internal val verifier: String) {
        override fun toString() = "AvatarOperatorConfiguration[redacted]"
    }
    private var previous: Loaded? = null
    private var requireAdvance = false

    /** Revokes first, including on missing/malformed/unsafe files. Owner must swap
     * the returned reader/runtime bundle before resuming Main dispatch. */
    @Synchronized fun reload(authority: AvatarAuthority,
                             replaceAccounts: (NativeAvatarAccountReader) -> Unit): Loaded {
        authority.revoke()
        try {
            val loaded = decode(files.configuration(), files.epoch(), runtime)
            previous?.let { old ->
                if (loaded.descriptor.configuration < old.descriptor.configuration || loaded.generation < old.generation ||
                    (loaded.hash != old.hash || requireAdvance) && loaded.descriptor.configuration <= old.descriptor.configuration ||
                    (loaded.grant != old.grant || loaded.verifier != old.verifier) && loaded.generation <= old.generation)
                    throw AvatarAuthority.Denied()
            }
            // No admission window with new grants but the previous native proofs.
            replaceAccounts(loaded.accounts)
            previous = loaded
            requireAdvance = false
            authority.install(loaded.authority)
            return loaded
        } catch (_: Exception) { throw AvatarAuthority.Denied() }
    }
    @Synchronized fun revoke(authority: AvatarAuthority) {
        authority.revoke(); requireAdvance = true
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

        internal fun decode(configuration: ByteArray, epoch: ByteArray, runtime: RuntimeIdentity): Loaded {
            try {
                if (configuration.size !in 1..262144 || epoch.size !in 1..4096) throw AvatarAuthority.Denied()
                val root = mapper.readTree(configuration)
                exact(root, setOf("schema", "enabled", "descriptor", "grant", "bindings", "historical"))
                enabled(root)
                val descriptor = descriptor(root["descriptor"])
                if (descriptor.installation != runtime.installation || descriptor.imageDigest != runtime.imageDigest ||
                    descriptor.buildChecksum != runtime.buildChecksum || descriptor.bootNonce != runtime.bootNonce)
                    throw AvatarAuthority.Denied()
                val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(configuration))
                val guard = mapper.readTree(epoch)
                exact(guard, setOf("schema", "enabled", "descriptor", "configurationSha256")); enabled(guard)
                if (descriptor(guard["descriptor"]) != descriptor || text(guard, "configurationSha256") != hash)
                    throw AvatarAuthority.Denied()
                val grant = root["grant"]
                exact(grant, setOf("id", "generation", "verifierSha256"))
                val id = uuid(grant, "id"); val generation = number(grant, "generation", 1)
                val verifierHash = text(grant, "verifierSha256")
                val verifier = AvatarAuthority.Verifier.fromSha256(verifierHash)
                val current = mutableListOf<AvatarAuthority.CurrentGrant>()
                val proofs = mutableListOf<NativeAvatarAccountReader.Proof>()
                for (row in array(root, "bindings")) {
                    exact(row, setOf("userId", "issuer", "clientId", "subjectMode", "subject", "generation",
                        "nativeEpoch", "nativeRevision", "oidcRevision"))
                    val binding = AvatarAuthority.Binding(number(row, "userId", 1), text(row, "issuer"),
                        text(row, "clientId"), text(row, "subjectMode"), text(row, "subject"), number(row, "generation", 1))
                    val scope = AvatarLedger.Scope(descriptor.installation, descriptor.restore, descriptor.configuration,
                        descriptor.imageDigest, descriptor.buildChecksum, id, generation, binding.digest, binding.generation, binding.nativeId)
                    current += AvatarAuthority.CurrentGrant(scope, binding, verifier)
                    proofs += NativeAvatarAccountReader.Proof(binding, uuid(row, "nativeEpoch"),
                        number(row, "nativeRevision", 0), number(row, "oidcRevision", 0))
                }
                val historical = mutableListOf<AvatarAuthority.HistoricalGrant>()
                val usedVerifiers = mutableSetOf(verifierHash)
                for (row in array(root, "historical")) {
                    exact(row, setOf("installation", "operation", "intentDigest", "restore", "verifierSha256"))
                    val installation = uuid(row, "installation")
                    val recoveryVerifier = text(row, "verifierSha256")
                    if (installation != descriptor.installation || !usedVerifiers.add(recoveryVerifier)) throw AvatarAuthority.Denied()
                    historical += AvatarAuthority.HistoricalGrant(installation, uuid(row, "operation"),
                        text(row, "intentDigest"), uuid(row, "restore"), AvatarAuthority.Verifier.fromSha256(recoveryVerifier))
                }
                return Loaded(AvatarAuthority.TrustedConfiguration(current, historical), NativeAvatarAccountReader(proofs),
                    descriptor, hash, id, generation, verifierHash)
            } catch (_: Exception) { throw AvatarAuthority.Denied() } // Never expose parser input/source snippets.
        }
        private fun enabled(node: JsonNode) {
            if (number(node, "schema", 1) != 1L || node["enabled"]?.isBoolean != true || !node["enabled"].booleanValue())
                throw AvatarAuthority.Denied()
        }
        private fun descriptor(node: JsonNode): Descriptor {
            exact(node, setOf("installation", "restore", "configuration", "imageDigest", "buildChecksum", "bootNonce"))
            val image = text(node, "imageDigest"); val build = text(node, "buildChecksum")
            if (!image.matches(Regex("sha256:[0-9a-f]{64}")) || !build.matches(HASH)) throw AvatarAuthority.Denied()
            return Descriptor(uuid(node, "installation"), uuid(node, "restore"), number(node, "configuration", 1), image, build, uuid(node, "bootNonce"))
        }
        private fun exact(node: JsonNode?, keys: Set<String>) {
            if (node?.isObject != true || node.propertyNames().toSet() != keys) throw AvatarAuthority.Denied()
        }
        private fun text(node: JsonNode, key: String): String {
            val field = node[key] ?: throw AvatarAuthority.Denied()
            if (!field.isString) throw AvatarAuthority.Denied()
            return field.stringValue().also {
                if (it.isEmpty() || it.length > 1024 || it.any(Char::isISOControl)) throw AvatarAuthority.Denied()
            }
        }
        private fun number(node: JsonNode, key: String, minimum: Long): Long {
            val field = node[key] ?: throw AvatarAuthority.Denied()
            if (!field.isIntegralNumber || !field.canConvertToLong() || field.longValue() < minimum) throw AvatarAuthority.Denied()
            return field.longValue()
        }
        private fun uuid(node: JsonNode, key: String): UUID {
            val value = text(node, key); val parsed = UUID.fromString(value)
            if (parsed.toString() != value) throw AvatarAuthority.Denied()
            return parsed
        }
        private fun array(node: JsonNode, key: String): JsonNode {
            val field = node[key] ?: throw AvatarAuthority.Denied()
            if (!field.isArray || field.size() > 1024) throw AvatarAuthority.Denied()
            return field
        }
    }
}
