package org.gameyfin.app.users.avatar

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.apache.catalina.startup.Tomcat
import org.flywaydb.core.Flyway
import org.gameyfin.app.core.security.EncryptionUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

/** One bounded actual HTTP/native SQL run. Not browser OIDC, private-file admission,
 * production connector configuration, Main custody or independent restore acceptance. */
class AvatarMachineLoopbackTest {
    @Test fun `real loopback preserves native identity CAS original receipt and independent users`(@TempDir directory: Path) {
        mockkObject(EncryptionUtils)
        every { EncryptionUtils.getAppKey() } returns Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })
        val url = "jdbc:h2:mem:avatar-http-${UUID.randomUUID()}"
        val keeper = DriverManager.getConnection(url, "sa", "")
        val tomcat = Tomcat()
        try {
            Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate()
            keeper.createStatement().use { it.executeUpdate("INSERT INTO users(id,username,email,email_confirmed,enabled,oidc_provider_id) " +
                "VALUES(1,'one','one@example.invalid',TRUE,TRUE,'subject-one'),(2,'two','two@example.invalid',TRUE,TRUE,'subject-two')") }
            val issuer = "https://issuer.invalid"; val client = "fixture-client"
            for ((key, value) in mapOf("sso.oidc.enabled" to true, "sso.oidc.issuer-url" to issuer, "sso.oidc.client-id" to client)) {
                keeper.prepareStatement("MERGE INTO app_config(\"key\",\"value\") KEY(\"key\") VALUES(?,?)").use {
                    it.setString(1, key); it.setString(2, EncryptionUtils.encrypt(ObjectMapper().writeValueAsString(value))); it.executeUpdate()
                }
            }
            val bindings = (1L..2L).map { AvatarAuthority.Binding(it, issuer, client, "public", if (it == 1L) "subject-one" else "subject-two", 1) }
            val oidcRevision = keeper.createStatement().use { it.executeQuery("SELECT revision FROM avatar_oidc_fence WHERE id=1").use { rows -> rows.next(); rows.getLong(1) } }
            val proofs = bindings.map { binding -> keeper.prepareStatement("SELECT avatar_identity_epoch,avatar_identity_revision FROM users WHERE id=?").use {
                it.setLong(1, binding.nativeId); it.executeQuery().use { rows -> check(rows.next())
                    NativeAvatarAccountReader.Proof(binding, rows.getObject(1, UUID::class.java), rows.getLong(2), oidcRevision) }
            } }
            val installation = UUID.randomUUID(); val restore = UUID.randomUUID(); val grant = UUID.randomUUID()
            val scopes = bindings.map { AvatarLedger.Scope(installation, restore, 1, "sha256:" + "a".repeat(64), "b".repeat(64), grant, 1, it.digest, 1, it.nativeId) }
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 28 })
            val recoverySecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 29 })
            val authority = AvatarAuthority().apply { install(AvatarAuthority.TrustedConfiguration(scopes.zip(bindings).map { (scope, binding) ->
                AvatarAuthority.CurrentGrant(scope, binding, AvatarAuthority.Verifier.fromSecret(secret)) }, emptyList())) }
            val dataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
                check(method.name == "getConnection"); DriverManager.getConnection(url, "sa", "") } as DataSource
            val reader = NativeAvatarAccountReader(proofs)
            val owner = AvatarTransactions(dataSource, authority, AvatarLedger(), nativeAccount = reader::read)
            val operations = object : AvatarMachineOperations {
                override fun preflight(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit) = owner.preflight(secret, scope, requestFence)
                override fun current(secret: String, scope: AvatarLedger.Scope, requestFence: () -> Unit) = owner.current(secret, scope, requestFence)
                override fun apply(secret: String, intent: AvatarLedger.Intent, png: CanonicalAvatarPng?, requestFence: () -> Unit) = owner.apply(secret, intent, png, requestFence)
                override fun originalReceipt(secret: String, installation: UUID, operation: UUID, intentDigest: String, restore: UUID, requestFence: () -> Unit) =
                    owner.originalReceipt(secret, installation, operation, intentDigest, restore, requestFence)
            }
            tomcat.setBaseDir(Files.createDirectory(directory.resolve("tomcat")).toString())
            tomcat.setPort(0)
            val connector = tomcat.connector
            for ((key, value) in mapOf("address" to "127.0.0.1", "maxThreads" to "4", "minSpareThreads" to "1",
                "maxConnections" to "8", "acceptCount" to "2", "connectionTimeout" to "2000",
                "keepAliveTimeout" to "1000", "maxKeepAliveRequests" to "4", "maxHttpRequestHeaderSize" to "8192",
                "disableUploadTimeout" to "false", "connectionUploadTimeout" to "2000")) check(connector.setProperty(key, value))
            val context = tomcat.addContext("", Files.createDirectory(directory.resolve("web")).toString())
            context.parentClassLoader = javaClass.classLoader
            Tomcat.addServlet(context, "avatar", AvatarMachineServlet(operations)).isAsyncSupported = true
            context.addServletMappingDecoded("/internal/avatar/v1/*", "avatar")
            tomcat.start()
            val port = connector.localPort
            fun scopeMap(s: AvatarLedger.Scope) = mapOf("installation" to s.installation.toString(), "restore" to s.restore.toString(),
                "configuration" to s.configuration, "imageDigest" to s.imageDigest, "buildChecksum" to s.buildChecksum,
                "grant" to s.grant.toString(), "grantGeneration" to s.grantGeneration, "bindingDigest" to s.bindingDigest,
                "bindingGeneration" to s.bindingGeneration, "userId" to s.userId)
            fun intentMap(i: AvatarLedger.Intent) = mapOf("scope" to scopeMap(i.scope), "operation" to i.operation.toString(),
                "expectedVersion" to i.expectedVersion, "expectedHash" to i.expectedHash, "sourceGeneration" to i.sourceGeneration, "desiredHash" to i.desiredHash)
            fun call(method: String, path: String, pin: Any, body: ByteArray = byteArrayOf(), bearer: String? = secret): Pair<Int, String> =
                Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                    socket.soTimeout = 4000
                    val headers = buildString {
                        append("$method /internal/avatar/v1/$path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n")
                        append("Content-Length: ${body.size}\r\nContent-Type: image/png\r\n")
                        append("${AvatarMachineProtocol.HEADER}: ${String(AvatarMachineProtocol.json(pin), Charsets.UTF_8)}\r\n")
                        if (bearer != null) append("Authorization: Bearer $bearer\r\n") else append("Cookie: JSESSIONID=synthetic\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
                    val response = String(socket.getInputStream().readNBytes(16385), Charsets.UTF_8)
                    require(response.length < 16385)
                    assertFalse(response.contains("Set-Cookie:", ignoreCase = true)); assertFalse(response.contains("Location:", ignoreCase = true))
                    response.substringBefore("\r\n").split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
                }
            assertEquals(400, call("GET", "current", mapOf("scope" to scopeMap(scopes[0])), bearer = null).first)
            assertEquals(200, call("GET", "current", mapOf("scope" to scopeMap(scopes[0]))).first)
            val bytes = png(); val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val first = AvatarLedger.Intent(scopes[0], UUID.randomUUID(), AvatarLedger.Action.REPLACE, 0, null, 1, hash)
            val written = call("PUT", "avatar", intentMap(first), bytes)
            assertEquals(200, written.first); assertTrue(written.second.contains(first.digest()))
            assertEquals(written, call("PUT", "avatar", intentMap(first), bytes))
            assertEquals(409, call("PUT", "avatar", intentMap(first.copy(sourceGeneration = 2)), bytes).first)
            assertEquals(403, call("GET", "current", mapOf("scope" to scopeMap(scopes[1].copy(bindingDigest = scopes[0].bindingDigest)))).first)
            val second = first.copy(scope = scopes[1], operation = UUID.randomUUID())
            assertEquals(200, call("PUT", "avatar", intentMap(second), bytes).first)
            NativeAvatarState(dataSource).setPrepared(1, null)
            assertEquals(written, call("PUT", "avatar", intentMap(first), bytes)) // Original, not current.
            val current = call("GET", "current", mapOf("scope" to scopeMap(scopes[0])))
            assertEquals(200, current.first); assertTrue(current.second.contains("\"kind\":\"ABSENT\"")); assertTrue(current.second.contains("\"version\":2"))
            assertEquals(hash, owner.current(secret, scopes[1]).hash) // Independent second user's content survives.
            val remove = AvatarLedger.Intent(scopes[1], UUID.randomUUID(), AvatarLedger.Action.REMOVE, 1, hash, 2, null)
            assertEquals(200, call("DELETE", "avatar", intentMap(remove)).first)
            keeper.createStatement().use { it.executeUpdate("UPDATE users SET oidc_provider_id='wrong-subject' WHERE id=1") }
            assertEquals(403, call("GET", "current", mapOf("scope" to scopeMap(scopes[0]))).first)
            authority.revoke()
            assertEquals(403, call("GET", "current", mapOf("scope" to scopeMap(scopes[1]))).first)
            keeper.createStatement().use { it.executeUpdate("DELETE FROM users WHERE id=1") }
            authority.install(AvatarAuthority.TrustedConfiguration(emptyList(), listOf(AvatarAuthority.HistoricalGrant(
                installation, first.operation, first.digest(), restore, AvatarAuthority.Verifier.fromSecret(recoverySecret)))))
            val recoveryPin = mapOf("installation" to installation.toString(), "operation" to first.operation.toString(), "intentDigest" to first.digest(), "restore" to restore.toString())
            val recovered = call("GET", "receipt", recoveryPin, bearer = recoverySecret)
            assertEquals(200, recovered.first); assertTrue(recovered.second.contains("COMMITTED")); assertTrue(recovered.second.contains(first.digest()))
            assertEquals(403, call("GET", "current", mapOf("scope" to scopeMap(scopes[0])), bearer = recoverySecret).first)
        } finally {
            try { tomcat.stop() } finally { try { tomcat.destroy() } finally { keeper.close(); unmockkAll() } }
        }
    }
    private fun png(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        return try { ByteArrayOutputStream().use { check(javax.imageio.ImageIO.write(image, "PNG", it)); it.toByteArray() } }
        finally { image.flush() }
    }
}
