package org.gameyfin.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.test.*

/** Reviewed PR-image only, no Spring context, Hibernate or current-source migration execution.
 * All users/files/SQL are newly generated synthetic state. Never accepts archives or paths
 * from an operator and never rebuilds, publishes or patches the application image.
 */
@EnabledIfEnvironmentVariable(named = "GAMEYFIN_ORIGINAL_EDITOR_FIXTURE", matches = "reviewed-pr-synthetic-only")
class OriginalEditorImageFixtureTest {
    @Test @Timeout(value = 7, unit = TimeUnit.MINUTES)
    fun `reviewed PR original editor saves canonical selection and immediate clicked ZIP`() {
        FixtureRuntime.hosted()
        require(System.getProperty("os.name") == "Linux")
        val repository = FixtureRuntime.repository()
        val imageId = requireNotNull(System.getenv("BUILT_IMAGE_ID"))
        val sourceHead = requireNotNull(System.getenv("REVIEWED_HEAD"))
        val expectedVersion = requireNotNull(System.getenv("EXPECTED_VERSION"))
        require(imageId.matches(Regex("sha256:[a-f0-9]{64}")))
        require(sourceHead.matches(Regex("[a-f0-9]{40}")))
        require(expectedVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?")))
        require(expectedVersion.length <= 64)
        val migrationVersions = Files.list(repository.resolve("app/src/main/resources/db/migration")).use { paths ->
            paths.map { it.fileName.toString() }.filter { it.matches(Regex("V[0-9]+(?:\\.[0-9]+)*__.+\\.sql")) }
                .map { it.substringAfter("V").substringBefore("__") }.toList().sorted()
        }
        require(migrationVersions.isNotEmpty() && migrationVersions.distinct().size == migrationVersions.size)
        val output = repository.resolve(".editor-candidate/result.json")
        require(!Files.exists(output))
        Files.createDirectories(output.parent)
        val root = FixtureRuntime.temporary("gameyfin-editor-")
        val owned = FixtureContainers(root)
        val proof = linkedMapOf<String, Any?>("scope" to "reviewed PR tiny original-UI acceptance only",
            "applicationImageId" to imageId, "sourceRevision" to sourceHead, "expectedVersion" to expectedVersion,
            "expectedMigrationVersions" to migrationVersions, "success" to false, "stage" to "admission",
            "cleanupConfirmed" to false, "fixtureOwner" to owned.owner)
        var relay: FixtureRelay? = null
        var failure: Throwable? = null
        fun receipt() { Files.writeString(output, FixtureRuntime.json.writeValueAsString(proof) + "\n") }
        try {
            receipt() // Cleanup identity is durable before any external resource creation.
            val deadline = FixtureDeadline(360)
            val image = FixtureRuntime.tree(owned.docker("image", "inspect", imageId))[0]
            require(image["Id"].asText() == imageId)
            require(image["Config"]["Labels"]["org.opencontainers.image.source"].asText() == "https://github.com/Niborian/gameyfin")
            require(image["Config"]["Labels"]["org.opencontainers.image.version"].asText() == expectedVersion)
            require(image["Config"]["Labels"]["org.opencontainers.image.revision"].asText() == sourceHead)
            val source = Files.createDirectory(root.resolve("source"))
            val base = Files.createDirectories(source.resolve("Normal 1.0/Base"))
            val older = Files.createDirectory(source.resolve("Normal 0.9"))
            val a = "Synthetic grouped base A\n".toByteArray(); val b = "Synthetic grouped base B\n".toByteArray()
            val patch = "Synthetic optional patch\n".toByteArray()
            Files.write(base.resolve("base-a.bin"), a); Files.write(base.resolve("base-b.bin"), b)
            Files.write(source.resolve("Normal 1.0/patch.bin"), patch)
            Files.writeString(older.resolve("old.bin"), "Synthetic older version\n")
            val before = ScanFixture.inventory(source)
            val expectedZip = mapOf("Base/base-a.bin" to ScanFixture.sha(a), "Base/base-b.bin" to ScanFixture.sha(b), "patch.bin" to ScanFixture.sha(patch))
            val state = listOf("db", "data", "logs", "plugindata").associateWith { name ->
                Files.createDirectory(root.resolve(name)).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxrwx")) }
            }
            fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val password = secret(); val viewerPassword = secret()
            val env = root.resolve("runtime.env")
            Files.writeString(env, "APP_KEY=${Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))}\nAPP_URL=http://localhost:8080\n")
            Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"))
            owned.createNetwork()
            val mounts = state.flatMap { (name, path) -> listOf("--mount", "type=bind,src=$path,dst=/opt/gameyfin/$name") } +
                listOf("--mount", "type=bind,src=$source,dst=/fixture,readonly")
            val app = owned.create("app", imageId, listOf("--cpus", "1", "--memory", "1536m", "--memory-swap", "1536m", "--pids-limit", "256",
                "--restart", "no", "--env", "GAMEYFIN_AVATAR_OVERLAY_ENABLED=true",
                "--env", "GAMEYFIN_AVATAR_MACHINE_ENABLED=false", "--env", "GAMEYFIN_AVATAR_OPERATOR_ENABLED=false",
                "--env-file", env.toString()) + mounts)
            val appState = FixtureRuntime.tree(owned.docker("inspect", app))[0]
            require(appState["HostConfig"]["NanoCpus"].longValue() == 1_000_000_000L)
            require(appState["HostConfig"]["Memory"].longValue() == 1536L * 1024 * 1024)
            relay = FixtureRelay(owned.address(app), 8080)
            val http = FixtureHttp(relay.origin, deadline = { deadline.remaining() })
            fun csrf(text: String) = Regex("<meta[^>]*name=\"_csrf\"[^>]*content=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            fun ready(path: String = "/login"): String {
                require(path in setOf("/setup", "/login"))
                proof["readinessPath"] = path
                var value: String? = null
                FixtureRuntime.await(120, { deadline.remaining() }) {
                    val response = http.request(path, seconds = 3)
                    value = csrf(response.bytes.toString(Charsets.UTF_8))
                    proof["lastReadinessHttpStatus"] = response.status
                    proof["readinessCsrfPresent"] = value != null
                    response.status == 200 && value != null
                }
                return requireNotNull(value)
            }
            // Published SetupFilter redirects /login to /setup before the first
            // administrator exists. Observe the real setup page, not a redirect.
            var token = ready("/setup")
            fun pluginsReady() {
                val health = FixtureHttp("http://${owned.address(app)}:8081", deadline = { deadline.remaining() })
                FixtureRuntime.await(60, { deadline.remaining() }) {
                    val response = health.request("/actuator/health/readiness", seconds = 3)
                    proof["lastPluginReadinessHttpStatus"] = response.status
                    response.status == 200 && FixtureRuntime.tree(response.bytes.toString(Charsets.UTF_8))["status"]?.asText() == "UP"
                }
            }
            fun rpc(endpoint: String, body: Any): ByteArray {
                val response = http.request("/connect/$endpoint", FixtureRuntime.json.writeValueAsString(body),
                    mapOf("Content-Type" to "application/json", "X-CSRF-Token" to token), seconds = minOf(10, deadline.remaining()))
                require(response.status in 200..299) { "Synthetic setup endpoint refused" }; return response.bytes
            }
            proof["stage"] = "fresh-setup"
            rpc("SetupEndpoint/registerSuperAdmin", mapOf("superAdminRegistration" to mapOf("username" to "fixture-admin", "password" to password, "email" to "admin@example.invalid")))
            fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)
            fun login(username: String, pass: String) {
                token = ready()
                val reply = http.request("/login", "username=${encode(username)}&password=${encode(pass)}&_csrf=${encode(token)}",
                    mapOf("Content-Type" to "application/x-www-form-urlencoded"), seconds = 10)
                require(reply.status in 200..399) { "Synthetic setup login refused" }
                token = requireNotNull(csrf(http.request("/", seconds = 5).bytes.toString(Charsets.UTF_8)))
                val identity = FixtureRuntime.tree(rpc("UserEndpoint/getUserInfo", emptyMap<String, Any>()).toString(Charsets.UTF_8))
                require(identity["username"]?.asText() == username && identity["enabled"]?.booleanValue() == true &&
                    (identity["id"]?.longValue() ?: 0L) > 0L) { "Synthetic session identity not established" }
            }
            login("fixture-admin", password)
            proof["stage"] = "plugin-readiness"
            pluginsReady(); proof["pluginsReadyBeforeEnable"] = true
            proof["stage"] = "fresh-setup"
            rpc("PluginEndpoint/enablePlugin", mapOf("pluginId" to "org.gameyfin.plugins.download.direct"))
            val providers = FixtureRuntime.tree(rpc("DownloadProviderEndpoint/getProviders", emptyMap<String, Any>()).toString(Charsets.UTF_8))
            require(providers.isArray) { "Provider list has unexpected shape" }
            val providerKey = "org.gameyfin.plugins.download.direct.DirectDownloadPlugin\$DirectDownloadProvider"
            val direct = providers.filter { it["key"]?.asText() == providerKey }
            proof["providerCount"] = providers.size(); proof["directProviderCount"] = direct.size
            if (direct.size != 1) {
                val plugins = FixtureRuntime.tree(rpc("PluginEndpoint/getAll", emptyMap<String, Any>()).toString(Charsets.UTF_8))
                require(plugins.isArray)
                val matches = plugins.filter { it["id"]?.asText() == "org.gameyfin.plugins.download.direct" }
                proof["directPluginCount"] = matches.size
                matches.singleOrNull()?.let { plugin ->
                    proof["directPluginState"] = plugin["state"]?.asText()?.takeIf { it in setOf("CREATED", "DISABLED", "RESOLVED", "STARTED", "STOPPED", "FAILED", "UNLOADED") } ?: "OTHER"
                    proof["directPluginTrust"] = plugin["trustLevel"]?.asText()?.takeIf { it in setOf("UNKNOWN", "OFFICIAL", "BUNDLED", "THIRD_PARTY", "UNTRUSTED") } ?: "OTHER"
                }
            }
            val selectedProvider = direct.single()
            require(selectedProvider["name"]?.isTextual == true)
            val providerName = selectedProvider["name"].asText()
            require(providerName.isNotBlank() && providerName.length <= 128)
            val invitation = FixtureRuntime.tree(rpc("RegistrationEndpoint/createInvitation", mapOf("email" to "viewer@example.invalid")).toString(Charsets.UTF_8))["secret"].asText()
            val accepted = rpc("RegistrationEndpoint/acceptInvitation", mapOf("token" to invitation,
                "registration" to mapOf("username" to "fixture-user", "password" to viewerPassword, "email" to "viewer@example.invalid")))
            require(accepted.toString(Charsets.UTF_8).trim('"', ' ', '\n') == "SUCCESS")
            val h2 = owned.docker("exec", app, "find", "/opt/gameyfin", "-type", "f", "-name", "h2-*.jar").lines().single()
            require(h2.matches(Regex("/opt/gameyfin/(?:[A-Za-z0-9._-]+/)*h2-[0-9.]+\\.jar")))
            owned.docker("stop", "--time", "10", app)
            require(!FixtureRuntime.tree(owned.docker("inspect", app))[0]["State"]["Running"].booleanValue())
            proof["stage"] = "offline-image-schema-seed"
            val sql = root.resolve("seed.sql")
            Files.writeString(sql, """
                SELECT 1 / CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END FROM "flyway_schema_history" WHERE "success" = FALSE;
                SELECT 1 / CASE WHEN COUNT(*) = ${migrationVersions.size} THEN 1 ELSE 0 END FROM "flyway_schema_history" WHERE "version" IS NOT NULL;
                ${migrationVersions.joinToString("\n") { version -> "SELECT 1 / CASE WHEN COUNT(*) = 1 THEN 1 ELSE 0 END FROM \"flyway_schema_history\" WHERE \"version\" = '$version' AND \"success\" = TRUE;" }}
                INSERT INTO LIBRARY(ID,CREATED_AT,NAME,UPDATED_AT,STORAGE_MODE) VALUES(27001,CURRENT_TIMESTAMP,'Synthetic editor library',CURRENT_TIMESTAMP,'DIRECT');
                INSERT INTO DIRECTORY_MAPPING(ID,EXTERNAL_PATH,INTERNAL_PATH) VALUES(27001,'/fixture','/fixture');
                INSERT INTO LIBRARY_DIRECTORIES(LIBRARY_ID,DIRECTORIES_ID) VALUES(27001,27001);
                INSERT INTO GAME(ID,CREATED_AT,TITLE,UPDATED_AT,PATH,FILE_SIZE,LIBRARY_ID,DOWNLOAD_COUNT,MATCH_CONFIRMED) VALUES(34000,CURRENT_TIMESTAMP,'Synthetic editor game',CURRENT_TIMESTAMP,'/fixture',${a.size + b.size + patch.size},27001,0,TRUE);
                INSERT INTO GAME_VARIANT(ID,FILE_SIZE,IS_DEFAULT,IS_LATEST_FOR_VARIANT,NAME,PATH,VERSION,GAME_ID,SCAN_MANAGED,DEFAULT_LOCKED,LINK_STATUS) VALUES
                (134000,24,TRUE,FALSE,'Normal','/fixture/Normal 0.9','0.9',34000,FALSE,TRUE,'DIRECT'),
                (134001,${a.size + b.size + patch.size},FALSE,TRUE,'Normal','/fixture/Normal 1.0','1.0',34000,FALSE,FALSE,'DIRECT');
                INSERT INTO VARIANT_DETAILS(VARIANT_ID,GAME_ID,EDITION,LIBRARY_VERSION,MANUAL_OVERRIDE,REVISION)
                VALUES(134001,34000,'Survivors of the Void + Seekers of the Storm + Alloyed Collective + Hallowed Concepts','1.5.0',TRUE,1);
                UPDATE GAME_VARIANT SET STEAM_APP_ID='244850',STEAM_APP_ID_VERIFIED_AT=CURRENT_TIMESTAMP WHERE ID=134001;
                INSERT INTO STEAM_NEWS_CHECK(APP_ID,ATTEMPTED_AT,SUCCEEDED_AT,FAILED) VALUES('244850',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,TRUE);
                INSERT INTO STEAM_NEWS_NOTICE(APP_ID,EVENT_ID,TITLE,EVENT_URL,PUBLISHED_AT,OBSERVED_AT,CLASSIFICATION,REASON,SOURCE,FEED_NAME,FEED_LABEL,TAGS) VALUES
                ('244850','90001','Synthetic patch notice','https://store.steampowered.com/news/app/244850/view/90001',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'CONTENT_UPDATE','Synthetic patch classification','Synthetic fixture (no provider call)','fixture','Synthetic feed','patchnotes'),
                ('244850','90002','Synthetic review notice','https://store.steampowered.com/news/app/244850/view/90002',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'REVIEW_NEEDED','Synthetic ambiguous classification','Synthetic fixture (no provider call)','fixture','Synthetic feed','');
                INSERT INTO VARIANT_CONTENT(ID,DEFAULT_SELECTED,FILE_SIZE,NAME,PATH,REQUIRED,TYPE,VARIANT_ID) VALUES
                (268000,TRUE,${a.size + b.size + patch.size},'Broad original','/fixture/Normal 1.0',TRUE,'BASE',134001),
                (268001,TRUE,24,'Older base','/fixture/Normal 0.9/old.bin',TRUE,'BASE',134000);
                INSERT INTO VARIANT_CONTENT_PATHS(VARIANT_CONTENT_ID,PATH_INDEX,PATH) VALUES(268000,0,'/fixture/Normal 1.0'),(268001,0,'/fixture/Normal 0.9/old.bin');
            """.trimIndent())
            val seeder = owned.create("seed", imageId, listOf("--cpus", "1", "--memory", "192m", "--memory-swap", "192m", "--pids-limit", "64",
                "--user", "1337:1337", "--entrypoint", "java", "--mount", "type=bind,src=${state.getValue("db")},dst=/db",
                "--mount", "type=bind,src=$sql,dst=/seed.sql,readonly"),
                listOf("-Xmx96m", "-cp", h2, "org.h2.tools.RunScript", "-url", "jdbc:h2:/db/gameyfin_db", "-user", "gfadmin", "-password", "gameyfin", "-script", "/seed.sql"))
            require(owned.docker("wait", seeder, seconds = 30) == "0") { "Synthetic offline seed failed" }
            owned.docker("start", app); ready(); pluginsReady()
            proof["pluginsReadyBeforeBrowser"] = true
            proof["stage"] = "original-browser"
            val script = root.resolve("original-editor-image.cts")
            Files.copy(repository.resolve("scripts/original-editor-image.cts"), script, StandardCopyOption.COPY_ATTRIBUTES)
            val avatar = root.resolve("synthetic-avatar.png")
            val pixels = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
            try {
                pixels.setRGB(0, 0, 0xff2176a5.toInt())
                require(ImageIO.write(pixels, "PNG", avatar.toFile()))
            } finally { pixels.flush() }
            val chrome = owned.create("browser", BROWSER, listOf("--interactive", "--user", "10042:10042", "--cap-drop", "ALL", "--read-only",
                "--cpus", "1", "--memory", "768m", "--memory-swap", "768m", "--pids-limit", "256",
                "--mount", "type=bind,src=$script,dst=/opt/original-editor-image.cts,readonly",
                "--mount", "type=bind,src=$avatar,dst=/opt/synthetic-avatar.png,readonly",
                "--env", "GAMEYFIN_ORIGINAL_EDITOR=reviewed-pr-synthetic-only", "--env", "XDG_CONFIG_HOME=/tmp/config", "--env", "XDG_CACHE_HOME=/tmp/cache",
                "--entrypoint", "node"), listOf("--experimental-strip-types", "/opt/original-editor-image.cts"), browserTmpfs = true)
            val input = FixtureRuntime.json.writeValueAsString(mapOf("origin" to "http://${owned.address(app)}:8080", "username" to "fixture-admin",
                "password" to password, "viewerPassword" to viewerPassword, "providerName" to providerName, "providerKey" to providerKey, "expectedVersion" to expectedVersion))
            val privateResult = browserPipe(chrome, input)
            val browserProof = privateResult["proof"]
            val expectedFlags = listOf("success", "loginWithin15Seconds", "nonDefaultPreserved", "unrelatedDraftPreserved", "expectedNativeConfirmAccepted",
                "immediateCanonicalCards", "clickedExactDownload", "ordinaryUserEditorAbsent", "ordinarySavedDetailsVisible", "adminProfileIdentityVerified", "ordinaryProfileIdentityVerified",
                "keepEditingPreserved", "escapePreserved", "escapeFocusRestored", "keepEditingFocusRestored", "controlledDiscardAccepted",
                "viewerLogoutObserved", "loggedOutLoginFormObserved", "repeatedViewerIdentityVerified", "cleanupConfirmed",
                "ordinaryAvatarChooserUsed", "ordinaryAvatarUploadRendered", "ordinaryAvatarRemoveRendered", "ordinaryAvatarNoReload",
                "steamInboxRendered", "steamInboxSnoozePersisted", "steamInboxIgnorePersisted", "steamInboxCheckStatusVisible")
            proof["browser"] = expectedFlags.associateWith { browserProof[it]?.booleanValue() == true }
            val browserStage = browserProof["stage"]?.asText()
            require(browserStage in setOf("input", "launch", "normal-login", "editor", "immediate-download", "steam-inbox", "ordinary-user", "ordinary-avatar", "repeat-login", "complete"))
            proof["browserStage"] = browserStage
            val probe = browserProof["currentSessionProbe"]
            require(probe.isObject && probe.properties().map { it.key }.toSet() ==
                setOf("kind", "status", "currentIdentityMatches", "profileNull"))
            val probeKind = probe["kind"].asText()
            require(probeKind in setOf("not-run", "completed", "timeout", "error", "input-refused", "body-refused", "json-refused"))
            val probeStatus = probe["status"]
            require(probeStatus.isNull || probeStatus.isIntegralNumber && probeStatus.canConvertToInt() && probeStatus.intValue() in 100..599)
            require(probe["currentIdentityMatches"].isBoolean && probe["profileNull"].isBoolean)
            require(!(probe["currentIdentityMatches"].booleanValue() && probe["profileNull"].booleanValue()))
            require((!probe["currentIdentityMatches"].booleanValue() && !probe["profileNull"].booleanValue()) ||
                probeKind == "completed" && !probeStatus.isNull && probeStatus.intValue() == 200)
            proof["currentSessionProbe"] = mapOf("kind" to probeKind,
                "status" to if (probeStatus.isNull) null else probeStatus.intValue(),
                "currentIdentityMatches" to probe["currentIdentityMatches"].booleanValue(), "profileNull" to probe["profileNull"].booleanValue())
            val repeatPhase = browserProof["repeatPhase"]?.asText()
            require(repeatPhase in setOf("not-started", "profile-menu", "sign-out", "logged-out-form", "normal-form", "complete"))
            proof["repeatPhase"] = repeatPhase
            val loginPhase = browserProof["loginPhase"]?.asText()
            require(loginPhase in setOf("not-started", "opening-login", "form-ready", "submitted", "profile-response", "authenticated-landing", "profile-menu", "complete"))
            proof["loginPhase"] = loginPhase
            proof["loginObservations"] = listOf("profileResponseObserved", "profileIdentityMatched", "authenticatedLandingObserved",
                "profileTriggerObserved", "profileDomIdentityMatched").associateWith { browserProof[it]?.booleanValue() == true }
            val editorPhase = browserProof["editorPhase"]?.asText()
            require(editorPhase in setOf("not-started", "opening-game", "game-ready", "version-trigger", "version-options", "selected-version",
                "editor-open", "split-preview", "base-classification", "type-trigger", "type-options", "draft", "replace", "replacement-response", "canonical-check",
                "draft-preserved", "closing-editor", "cards", "complete"))
            proof["editorPhase"] = editorPhase
            val closePhase = browserProof["closePhase"]?.asText()
            require(closePhase in setOf("not-started", "first-close", "first-prompt", "initial-focus", "escape", "escape-result",
                "second-close", "second-prompt", "keep-editing", "keep-result", "third-close", "third-prompt", "discard", "complete"))
            proof["closePhase"] = closePhase
            proof["gameDiagnostics"] = validateEditorGameDiagnostics(browserProof["gameDiagnostics"])
            val clickKeys = setOf("observed", "preExists", "preEnabled", "preInViewport", "preCenterOwn", "preCenterToast",
                "postExists", "postEnabled", "postInViewport", "postCenterOwn", "postCenterToast", "trustedCloseClick", "trustedOtherClick")
            val closeClick = browserProof["closeClick"]
            require(closeClick.isObject && closeClick.properties().map { it.key }.toSet() == clickKeys)
            proof["closeClick"] = clickKeys.associateWith { require(closeClick[it].isBoolean); closeClick[it].booleanValue() }
            proof["editorObservations"] = listOf("gameRouteMatches", "footerMatches", "versionTriggerUnique", "defaultVersionObserved",
                "targetOptionObserved", "targetVersionObserved", "editorDialogObserved", "splitPreviewObserved", "typeTriggerFound", "typeOptionsSeen",
                "editorClosed", "savedVersionShown", "savedBaseShown", "savedPatchShown", "broadOriginalAbsent",
                "controlledDiscardAccepted", "keepEditingPreserved", "escapePreserved", "escapeFocusRestored", "keepEditingFocusRestored", "unexpectedNativeDialog", "editorDialogStillPresent",
                "closeSnapshotObserved", "closePromptPresent", "closeKeepFocused", "closeButtonFocused", "closeDraftPresent")
                .associateWith { browserProof[it]?.booleanValue() == true }
            require(expectedFlags.all { browserProof[it]?.booleanValue() == true }) { "Original UI gate failed" }
            require(owned.docker("wait", chrome, seconds = 5) == "0")
            val encoded = privateResult["zip"].asText(); require(encoded.length <= 90000)
            val bytes = Base64.getDecoder().decode(encoded); require(bytes.size in 1..65536)
            val members = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
                var total = 0
                while (true) {
                    val entry = stream.nextEntry ?: break
                    require(!entry.isDirectory && members.size < 4 && entry.name.length <= 128)
                    val member = stream.readNBytes(1025); total += member.size
                    require(member.size <= 1024 && total <= 4096 && members.put(entry.name, ScanFixture.sha(member)) == null)
                }
            }
            require(members == expectedZip) { "Immediate clicked ZIP differs from exact synthetic selection" }
            proof["zipMemberHashesMatch"] = true
            require(before == ScanFixture.inventory(source)) { "Synthetic source bytes changed" }
            proof["sourceHashesUnchanged"] = true
            login("fixture-user", viewerPassword)
            val denied = http.request("/connect/GameEndpoint/setDefaultVariant", "{\"targetGameId\":34000,\"variantId\":134001}",
                mapOf("Content-Type" to "application/json", "X-CSRF-Token" to token), seconds = 5)
            require(denied.status == 403) { "Ordinary-user mutation was not denied" }
            proof["ordinaryUserMutationDenied"] = true
            for ((method, body) in listOf("inbox" to "{}", "ignore" to "{\"appId\":\"244850\",\"eventId\":\"90001\"}")) {
                val refusal = http.request("/connect/SteamNewsInboxEndpoint/$method", body,
                    mapOf("Content-Type" to "application/json", "X-CSRF-Token" to token), seconds = 5)
                require(refusal.status == 403) { "Ordinary-user Steam inbox access was not denied" }
            }
            proof["ordinarySteamInboxDenied"] = true
            val health = FixtureHttp("http://${owned.address(app)}:8081", deadline = { deadline.remaining() }).request("/actuator/health/readiness", seconds = 5)
            require(health.status == 200 && FixtureRuntime.tree(health.bytes.toString(Charsets.UTF_8))["status"].asText() == "UP")
            proof["readinessUp"] = true
            val final = FixtureRuntime.tree(owned.docker("inspect", app))[0]
            require(final["State"]["Running"].booleanValue() && !final["State"]["OOMKilled"].booleanValue() && final["RestartCount"].intValue() == 0)
            proof["noOomOrAutomaticRestart"] = true
            proof["success"] = true; proof["stage"] = "complete"
        } catch (error: Throwable) { failure = error; throw IllegalStateException("PR editor fixture did not meet acceptance; see safe receipt", error) }
        finally {
            var cleanup: Throwable? = null
            try { relay?.close() } catch (error: Throwable) { cleanup = error }
            try { owned.close() } catch (error: Throwable) { if (cleanup == null) cleanup = error else cleanup.addSuppressed(error) }
            try {
                if (cleanup != null) { proof["success"] = false; if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
                else proof["cleanupConfirmed"] = true
            }
            finally { receipt() }
        }
    }

    /** The ZIP and generated credentials exist only in bounded private process pipes. */
    private fun browserPipe(id: String, input: String): tools.jackson.databind.JsonNode {
        require(id.matches(Regex("[a-f0-9]{64}")) && input.length <= 2048)
        val process = ProcessBuilder("docker", "attach", "--sig-proxy=false", id).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val pool = Executors.newSingleThreadExecutor()
        val output = pool.submit<ByteArray> { process.inputStream.use { it.readNBytes(131073) } }
        try {
            process.outputStream.use { it.write((input + "\n").toByteArray()) }
            require(process.waitFor(100, TimeUnit.SECONDS)) { "Browser controller deadline" }
            val bytes = output.get(2, TimeUnit.SECONDS)
            require(bytes.size <= 131072)
            val value = try { FixtureRuntime.tree(bytes.toString(Charsets.UTF_8)) }
                catch (_: Exception) { throw IllegalStateException("Malformed private browser envelope") }
            require(value["kind"].asText() == "private-editor-result")
            return value
        } finally {
            if (process.isAlive) { process.destroy(); if (!process.waitFor(1, TimeUnit.SECONDS)) { process.destroyForcibly(); require(process.waitFor(2, TimeUnit.SECONDS)) } }
            output.cancel(true); pool.shutdownNow(); require(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
    companion object {
        const val BROWSER = "ghcr.io/puppeteer/puppeteer@sha256:893118e0546f8cee371a0ae09c8489d1e9c40de28c45df9d811d4233203b81a3"
    }
}

/** Closed, value-free navigation observation schema; never an acceptance predicate. */
internal fun validateEditorGameDiagnostics(node: tools.jackson.databind.JsonNode): tools.jackson.databind.JsonNode {
    val flags = setOf("truncated", "pageerrorSeen", "domObserved", "profilePresent", "signInPresent", "loginFormPresent", "errorHeadingPresent")
    require(node.isObject && node.properties().map { it.key }.toSet() == flags + setOf("phase", "families"))
    require(node["phase"].isTextual && node["phase"].asText() in setOf("not-started", "observing", "complete"))
    flags.forEach { require(node[it].isBoolean) }
    val families = node["families"]
    require(families.isObject && families.properties().map { it.key }.toSet() == setOf("game", "library", "user"))
    val observations = setOf("finished", "failed", "canceled", "continueAttempted", "continueFulfilled", "continueRejected",
        "bodyPending", "bodyClassified", "bodyUnavailable",
        "authNull", "authMatch", "arrayResponse", "targetPresent")
    families.properties().forEach { (_, row) ->
        require(row.isObject && row.properties().map { it.key }.toSet() == observations + setOf("count", "status"))
        require(row["count"].isIntegralNumber && row["count"].longValue() in 0L..4L)
        require(row["status"].isNull || row["status"].isIntegralNumber && row["status"].longValue() in 100L..599L)
        observations.forEach { require(row[it].isBoolean) }
    }
    return node
}
