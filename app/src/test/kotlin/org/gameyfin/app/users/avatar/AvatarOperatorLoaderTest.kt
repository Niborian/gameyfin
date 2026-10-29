package org.gameyfin.app.users.avatar

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.*

/** Owned synthetic operator files only. No deployed principal, credential or epoch custody proof. */
class AvatarOperatorLoaderTest {
    private val installation = UUID.randomUUID()
    private val restore = UUID.randomUUID()
    private val grant = UUID.randomUUID()
    private val nativeEpoch = UUID.randomUUID()
    private val bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 31 })
    private val verifier = hash(Base64.getUrlDecoder().decode(bearer))
    private val binding = AvatarAuthority.Binding(42, "https://issuer.invalid", "client", "public", "subject", 1)
    private val runtime = AvatarOperatorLoader.RuntimeIdentity.start(installation, "sha256:" + "a".repeat(64), "b".repeat(64))
    private val mapper = JsonMapper.builder().build()
    private fun config(revision: Long = 1, generation: Long = 1, verifierHash: String = verifier): String = """
        {"schema":1,"enabled":true,"descriptor":{"installation":"$installation","restore":"$restore",
        "configuration":$revision,"imageDigest":"${runtime.imageDigest}","buildChecksum":"${runtime.buildChecksum}","bootNonce":"${runtime.bootNonce}"},
        "grant":{"id":"$grant","generation":$generation,"verifierSha256":"$verifierHash"},
        "bindings":[{"userId":42,"issuer":"https://issuer.invalid","clientId":"client","subjectMode":"public",
        "subject":"subject","generation":1,"nativeEpoch":"$nativeEpoch","nativeRevision":0,"oidcRevision":0}],"historical":[]}
    """.trimIndent()
    private fun guard(configuration: String): ByteArray = """{"schema":1,"enabled":true,"descriptor":${mapper.readTree(configuration)["descriptor"]},"configurationSha256":"${hash(configuration.toByteArray())}"}""".toByteArray()
    private fun scope(revision: Long = 1, generation: Long = 1) = AvatarLedger.Scope(installation, restore, revision,
        runtime.imageDigest, runtime.buildChecksum, grant, generation, binding.digest, 1, 42)
    private fun account() = AvatarAuthority.NativeAccount(binding, true)

    @Test fun `strict verifier-only envelope and independently matching guard admit exact scope`() {
        val text = config()
        val loaded = AvatarOperatorLoader.decode(text.toByteArray(), guard(text), runtime)
        val authority = AvatarAuthority(); authority.install(loaded.authority)
        authority.acquireCurrent(bearer, scope(), ::account).use { it.checkCurrent() }
        assertFalse(text.contains(bearer)); assertFalse(loaded.toString().contains(verifier))
        assertFailsWith<AvatarAuthority.Denied> {
            AvatarOperatorLoader.decode(text.toByteArray(), guard(text).toString(Charsets.UTF_8)
                .replace(hash(text.toByteArray()), "f".repeat(64)).toByteArray(), runtime)
        }
        val operation = UUID.randomUUID()
        val recovery = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 32 })
        val recoveryVerifier = hash(Base64.getUrlDecoder().decode(recovery))
        val withRecovery = text.replace("\"historical\":[]", "\"historical\":[{\"installation\":\"$installation\",\"operation\":\"$operation\",\"intentDigest\":\"${"d".repeat(64)}\",\"restore\":\"$restore\",\"verifierSha256\":\"$recoveryVerifier\"}]")
        authority.install(AvatarOperatorLoader.decode(withRecovery.toByteArray(), guard(withRecovery), runtime).authority)
        authority.acquireHistorical(recovery, installation, operation, "d".repeat(64), restore).close()
        assertFailsWith<AvatarAuthority.Denied> { authority.acquireCurrent(recovery, scope(), ::account) }
        val reused = withRecovery.replace(recoveryVerifier, verifier)
        assertFailsWith<AvatarAuthority.Denied> { AvatarOperatorLoader.decode(reused.toByteArray(), guard(reused), runtime) }
    }

    @Test fun `unknown duplicate wrong-type and oversized inputs refuse even with recomputed guard`() {
        val valid = config()
        val malformed = listOf(
            valid.replace("\"schema\":1", "\"unexpected\":true,\"schema\":1"),
            valid.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":true"),
            valid.replace("\"userId\":42", "\"userId\":\"42\""),
            valid.replace("\"configuration\":1", "\"configuration\":1.5"),
            valid.replace("\"subject\":\"subject\"", "\"subject\":\"${"x".repeat(1025)}\""),
            valid.replace("\"historical\":[]", "\"historical\":[${List(1025) { "{}" }.joinToString(",")}]")
        )
        for (text in malformed) assertFailsWith<AvatarAuthority.Denied> {
            AvatarOperatorLoader.decode(text.toByteArray(), guard(text), runtime)
        }
        assertFailsWith<AvatarAuthority.Denied> { AvatarOperatorLoader.decode(ByteArray(262145), guard(valid), runtime) }
        assertFailsWith<AvatarAuthority.Denied> { AvatarOperatorLoader.decode(valid.toByteArray(), ByteArray(4097), runtime) }
        val trailing = valid + " {}"
        val matchingGuard = guard(valid).toString(Charsets.UTF_8).replace(hash(valid.toByteArray()), hash(trailing.toByteArray()))
        assertFailsWith<AvatarAuthority.Denied> { AvatarOperatorLoader.decode(trailing.toByteArray(), matchingGuard.toByteArray(), runtime) }
    }

    @Test fun `fresh runtime boot refuses previous boot configuration even when image and installation match`() {
        val text = config()
        val fresh = AvatarOperatorLoader.RuntimeIdentity.start(installation, runtime.imageDigest, runtime.buildChecksum)
        assertNotEquals(runtime.bootNonce, fresh.bootNonce)
        assertFailsWith<AvatarAuthority.Denied> { AvatarOperatorLoader.decode(text.toByteArray(), guard(text), fresh) }
    }

    @Test fun `reload revokes before reader swap and refuses rollback or failed replacement`() {
        val files = mockk<AvatarOperatorFiles>(); var text = config()
        every { files.configuration() } answers { text.toByteArray() }
        every { files.epoch() } answers { guard(text) }
        val loader = AvatarOperatorLoader(files, runtime); val authority = AvatarAuthority()
        var swaps = 0
        fun reload() = loader.reload(authority) {
            swaps++
            assertFailsWith<AvatarAuthority.Denied> { authority.acquireCurrent(bearer, scope(), ::account) }
        }
        reload(); authority.acquireCurrent(bearer, scope(), ::account).close()
        reload() // Identical configuration is idempotent before explicit revoke.
        loader.revoke(authority)
        assertFailsWith<AvatarAuthority.Denied> { reload() }
        text = config(2, 2); reload()
        authority.acquireCurrent(bearer, scope(2, 2), ::account).close()
        text = config(1, 1)
        assertFailsWith<AvatarAuthority.Denied> { reload() }
        assertFailsWith<AvatarAuthority.Denied> { authority.acquireCurrent(bearer, scope(2, 2), ::account) }
        text = config(3, 1)
        assertFailsWith<AvatarAuthority.Denied> { reload() }
        text = config(3, 2)
        assertFailsWith<AvatarAuthority.Denied> { loader.reload(authority) { throw IllegalStateException("Synthetic swap failure") } }
        assertFailsWith<AvatarAuthority.Denied> { authority.acquireCurrent(bearer, scope(3, 2), ::account) }
        assertEquals(3, swaps)
    }

    @Test fun `owned file adapter proves private POSIX admission or unsupported platform refusal`(@TempDir directory: Path) {
        val owner = Files.getOwner(directory)
        val adapter = AvatarOperatorFiles(directory.toAbsolutePath().normalize(), owner)
        val configuration = directory.resolve("avatar-config.json")
        val epoch = directory.resolve("avatar-epoch.json")
        Files.writeString(configuration, config()); Files.write(epoch, guard(config()))
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertFailsWith<AvatarAuthority.Denied> { adapter.configuration() }
            assertFailsWith<AvatarAuthority.Denied> { adapter.epoch() }
            return // Real unsupported-platform refusal, not a skipped POSIX success claim.
        }
        Files.setPosixFilePermissions(directory, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        for (file in listOf(configuration, epoch)) Files.setPosixFilePermissions(file,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        Files.newDirectoryStream(directory).use { opened ->
            if (opened !is java.nio.file.SecureDirectoryStream<*>) {
                assertFailsWith<AvatarAuthority.Denied> { adapter.configuration() }
                return
            }
        }
        assertContentEquals(config().toByteArray(), adapter.configuration())
        assertContentEquals(guard(config()), adapter.epoch())
        Files.setPosixFilePermissions(configuration, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ))
        assertFailsWith<AvatarAuthority.Denied> { adapter.configuration() }
        Files.setPosixFilePermissions(configuration, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }
    private fun hash(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
