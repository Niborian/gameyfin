package org.gameyfin.tools

import java.io.ByteArrayInputStream
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Disposable Linux image rehearsal, never production paths. No pulling, publishing or forced GC. */
object ImageScanRehearsal {
    private val json = ScanFixture.json
    @Suppress("UNCHECKED_CAST") private fun objectOf(text: String) = json.readValue(text, Map::class.java) as Map<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun rows(text: String) = json.readValue(text, List::class.java) as List<Map<String,Any?>>
    @Suppress("UNCHECKED_CAST") private fun Map<String,Any?>.objectAt(key: String) = getValue(key) as Map<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun Map<String,Any?>.rowsAt(key: String) = getValue(key) as List<Map<String,Any?>>
    private fun Map<String,Any?>.number(key: String) = (getValue(key) as Number).toLong()
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    private fun form(values: Map<String,String>) = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    private fun now() = System.nanoTime()/1e9
    private fun random(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private data class Result(val code: Int, val text: String)
    private fun command(args: List<String>, timeout: Long = 60, enforce: Boolean = true): Result {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(8*1024*1024+1) } }
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) {
                "Bounded rehearsal command timed out; inspect owned resource identity"
            }
            val bytes = output.get(3, TimeUnit.SECONDS)
            check(bytes.size <= 8*1024*1024) { "Rehearsal command output exceeded bound" }
            val result = Result(process.exitValue(), bytes.toString(Charsets.UTF_8).trim())
            if (enforce) check(result.code == 0) { "Rehearsal command failed (exit=${result.code})" }
            return result
        } finally {
            try {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        check(process.waitFor(2, TimeUnit.SECONDS)) { "Rehearsal process cleanup uncertain" }
                    }
                }
            } finally {
                try { process.inputStream.close() } finally { output.cancel(true) }
            }
        }
    }
    fun metric(name: String, sample: String, selector: String? = null): Double {
        val values = sample.lineSequence().filter { (it.startsWith("$name{") || it.startsWith("$name ")) && (selector == null || selector in it) }
            .map { it.substringAfterLast(' ').toDouble() }.toList()
        check(values.isNotEmpty()) { "Required synthetic telemetry meter absent: $name" }
        return values.sum()
    }
    fun requireScanQuiescence(sample: String) {
        check(metric("gameyfin_scans_active", sample) == 0.0 && metric("gameyfin_scans_draining", sample) == 0.0) {
            "Idle evidence invalid: active or draining scan workers observed" }
    }
    fun samplePostTaskIdle(duration: Int, interval: Int, measure: () -> Map<String,Any?>,
                           clock: () -> Double = ::now, pause: (Double) -> Unit = { Thread.sleep((it*1000).toLong()) }): Map<String,Any?> {
        require(duration in 1..900 && interval in 1..duration)
        val started = clock(); val samples = mutableListOf<Map<String,Any?>>()
        while (true) {
            samples += measure()+mapOf("elapsedSeconds" to clock()-started)
            val remaining = duration-(clock()-started)
            if (remaining <= 0) break
            pause(minOf(interval.toDouble(),remaining))
        }
        return mapOf("requestedSeconds" to duration, "sampleIntervalSeconds" to interval,
            "observedSeconds" to clock()-started, "samples" to samples,
            "scope" to "post-task idle synthetic workload; sampled observations, no forced GC", "targetsAreNotLimits" to true,
            "idleRssTargetBytes" to 500L*1024*1024, "typicalTaskRssTargetBytes" to 1024L*1024*1024)
    }
    fun verifyMissingRootFault(root: Path, request: (String,Any?) -> String, snapshot: () -> String, timeout: Double = 180.0): Map<String,Any?> {
        val source = root.resolve("fixture/sources/lib1"); val parked = root.resolve("fixture/fault-lib1")
        require(!Files.isSymbolicLink(source) && Files.isDirectory(source) && !Files.exists(parked))
        val before = rows(request("/connect/GameEndpoint/getAll", emptyMap<String,Any>())).sortedBy { it.number("id") }
        val initial = snapshot(); val completed = metric("gameyfin_scans_completed_total", initial); val failed = metric("gameyfin_scans_failed_total", initial)
        Files.move(source,parked)
        try {
            request("/connect/LibraryEndpoint/triggerScan",mapOf("scanType" to "FULL", "libraryIds" to listOf(27001)))
            val started = now(); var done = 0.0; var errors = 0.0; var observed = false
            while (now()-started < timeout) {
                val sample = snapshot(); done = metric("gameyfin_scans_completed_total",sample)-completed
                errors = metric("gameyfin_scans_failed_total",sample)-failed
                if (errors != 0.0 || done != 0.0) {
                    check(errors == 1.0 && done == 0.0) { "Missing root did not produce exactly one failed scan" }
                    if (metric("gameyfin_scans_active",sample) == 0.0) { observed=true; break }
                }
                Thread.sleep(500)
            }
            check(observed) { "Missing-root failure was not observed" }
            val after = rows(request("/connect/GameEndpoint/getAll",emptyMap<String,Any>())).sortedBy { it.number("id") }
            check(before == after) { "Failed scan changed synthetic game/variant/content records" }
            return mapOf("libraryId" to 27001, "failedLibraries" to errors, "completedLibraries" to done,
                "recordsUnchanged" to true, "gameCount" to after.size, "scope" to "generated synthetic paths only")
        } finally { Files.move(parked,source) }
    }
    fun optionProbe(name: String, uid: String, gid: String): List<String> = listOf("exec","--user","$uid:$gid",name,"sh","-c",
        "for task in /proc/[0-9]*/comm; do if [ \"\$(cat \"\$task\" 2>/dev/null)\" = java ]; then tr '\\000' '\\n' < \"\${task%/comm}/environ\" | sed -n \"s/^JDK_JAVA_OPTIONS=//p\"; fi; done")

    @JvmStatic fun main(args: Array<String>) {
        val flags = setOf("--interrupt-scan","--missing-root-fault")
        val options = linkedMapOf<String,String>(); var i=0
        while (i<args.size) { val key=args[i++]; require(key.startsWith("--") && key !in options); options[key]=if(key in flags) "true" else args[i++] }
        val allowed=flags+setOf("--image","--source-revision","--h2-jar","--metadata-plugin","--direct-plugin","--metadata-plugin-id","--output","--runtime-uid","--runtime-gid","--docker-prefix","--idle-seconds","--idle-sample-seconds","--external-probe","--proxy-image")
        require(options.keys.all { it in allowed })
        val image=options.getValue("--image"); val revision=options.getValue("--source-revision")
        val h2=options.getValue("--h2-jar"); val metadata=Path.of(options.getValue("--metadata-plugin")); val direct=Path.of(options.getValue("--direct-plugin"))
        val output=Path.of(options.getValue("--output")); val uid=options["--runtime-uid"]?:"1337"; val gid=options["--runtime-gid"]?:"1337"
        val idle=(options["--idle-seconds"]?:"0").toInt(); val interval=(options["--idle-sample-seconds"]?:"15").toInt()
        require(image.matches(Regex("(?:[^\\s]+@)?sha256:[a-f0-9]{64}")))
        require(revision=="baseline-unknown" || revision.matches(Regex("[a-f0-9]{40}")) && revision.any { it!='0' })
        require(!Files.exists(output) && Files.isRegularFile(metadata) && Files.isRegularFile(direct))
        require(h2.startsWith('/') && uid.matches(Regex("[0-9]+")) && gid.matches(Regex("[0-9]+")))
        require(idle in 0..900 && interval>0 && (idle==0 || interval<=idle))
        require(options["--external-probe"]==null || options["--external-probe"]=="proxy-exposure") { "Only the built-in reviewed proxy probe is supported" }
        // Prefix is an argv list, never shell-evaluated. Existing workflow uses none; sudo -n is explicitly supported.
        val prefix=(options["--docker-prefix"]?:"").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(prefix.isEmpty() || prefix==listOf("sudo","-n"))
        val dockerPrefix=prefix+"docker"
        fun docker(vararg arguments: String)=command(dockerPrefix+arguments).text
        val root=Files.createTempDirectory("gameyfin-synthetic-scan-")
        val token=HexFormatCompat.hex(random(8)); val name="gameyfin-scan-$token"; val network="gameyfin-scan-net-$token"
        val report=linkedMapOf<String,Any?>("scope" to "synthetic actual-image scan; not production acceptance", "image" to image,
            "sourceRevision" to revision, "sourceProvenanceKnown" to (revision!="baseline-unknown"),"heapLimit" to "512m","containerLimit" to "1536m")
        val scans=mutableListOf<Map<String,Any?>>(); report["scanResults"]=scans
        val jvm="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"
        val profile=linkedMapOf<String,Any?>("name" to "controlled-fixture-override","usesImageDefaults" to false,"configuredOptions" to jvm.split(' '),
            "interpretation" to "JAVA_OPTS_OVERRIDE replaces image defaults; no shipped periodic-GC/reclaim flags or AOT cache requested","effectiveFlagValuesObserved" to false)
        report["jvmProfile"]=profile
        fun writeReport() { output.toAbsolutePath().parent?.let { Files.createDirectories(it) }; Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n") }
        val noProxy=object: ProxySelector() { override fun select(uri: URI)=listOf(Proxy.NO_PROXY); override fun connectFailed(uri: URI,sa: SocketAddress,error: java.io.IOException) {} }
        val cookies=CookieManager(null,CookiePolicy.ACCEPT_ALL)
        val client=HttpClient.newBuilder().proxy(noProxy).cookieHandler(cookies).followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
        val telemetry=HttpClient.newBuilder().proxy(noProxy).connectTimeout(Duration.ofSeconds(10)).build()
        var base=""; var metrics=""; var csrf=""
        fun http(url: String, body: String?=null, formBody: Boolean=false, timeout: Long=30, meter: Boolean=false, limit: Int=8*1024*1024): ByteArray {
            val builder=HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(timeout))
            if(body!=null) builder.header("Content-Type",if(formBody) "application/x-www-form-urlencoded" else "application/json")
                .header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString(body))
            val started=now()
            val response=(if(meter) telemetry else client).send(builder.build(),HttpResponse.BodyHandlers.ofInputStream())
            return response.body().use { stream ->
                check(response.statusCode() in 200..299) { "Synthetic HTTP request failed (${response.statusCode()})" }
                val bodyRead=CompletableFuture.supplyAsync { stream.readNBytes(limit+1) }
                try {
                    val remaining=((timeout-(now()-started))*1000).toLong()
                    if(remaining<=0) throw java.net.http.HttpTimeoutException("Synthetic body deadline exceeded")
                    bodyRead.get(remaining,TimeUnit.MILLISECONDS).also { check(it.size<=limit) { "Synthetic HTTP response exceeded bound" } }
                } catch (_: java.util.concurrent.TimeoutException) {
                    stream.close(); bodyRead.cancel(true)
                    throw java.net.http.HttpTimeoutException("Synthetic body deadline exceeded")
                } finally {
                    if(!bodyRead.isDone) { stream.close(); bodyRead.cancel(true) }
                }
            }
        }
        fun request(path: String, body: Any?=null)=http(base+path,body?.let { json.writeValueAsString(it) }).toString(Charsets.UTF_8)
        fun snapshot(): String {
            val included="jvm_memory_used_bytes,gameyfin_scans_active,gameyfin_scans_draining,gameyfin_scans_completed,gameyfin_scans_completed_total,gameyfin_scans_failed,gameyfin_scans_failed_total"
            return http("$metrics?includedNames=${encode(included)}",timeout=10,meter=true,limit=2*1024*1024).toString(Charsets.UTF_8)
        }
        fun csrfIn(page: String)=Regex("<meta[^>]*name=\"_csrf\"[^>]*content=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        fun ready(path: String="/setup"): String {
            repeat(180) { try { csrfIn(http(base+path,timeout=5).toString(Charsets.UTF_8))?.let { return it } } catch (_: Exception) {}; Thread.sleep(1000) }
            error("Isolated application startup timeout")
        }
        fun inspect()=rows(docker("inspect",name)).single()
        fun javaPid(): Long {
            val lines=docker("top",name,"-eo","pid,args").lineSequence().drop(1).map { it.trim().split(Regex("\\s+"),limit=2) }
                .filter { it.size==2 && "java" in it[1].substringBefore(' ') }.toList()
            check(lines.size==1) { "Require one isolated JVM for RSS evidence" }; return lines.single()[0].toLong()
        }
        fun rss(pid: Long): Long = Regex("VmRSS:\\s+(\\d+)").find(Files.readString(Path.of("/proc/$pid/status")))?.groupValues?.get(1)?.toLong()?.times(1024)
            ?: error("RSS unavailable; do not fabricate evidence")
        try {
            ScanFixture.generate(root.resolve("fixture"),"/fixture")
            val manifest=objectOf(Files.readString(root.resolve("fixture/manifest.json")))
            val expected=manifest.rowsAt("games").associateBy { it.number("gameId") }
            for(directory in listOf("db","data","logs","plugins","plugindata")) {
                Files.createDirectory(root.resolve(directory)); Files.setPosixFilePermissions(root.resolve(directory),PosixFilePermissions.fromString("rwxrwxrwx"))
            }
            Files.copy(metadata,root.resolve("plugins").resolve(metadata.fileName)); Files.copy(direct,root.resolve("plugins").resolve(direct.fileName))
            val env=root.resolve("runtime.env")
            Files.writeString(env,"APP_KEY=${Base64.getEncoder().encodeToString(random(32))}\nAPP_URL=http://localhost:8080\nJAVA_OPTS_OVERRIDE=$jvm\n",StandardOpenOption.CREATE_NEW)
            Files.setPosixFilePermissions(env,PosixFilePermissions.fromString("rw-------"))
            val imageData=rows(docker("image","inspect",image)).single()
            @Suppress("UNCHECKED_CAST") val labels=(imageData.objectAt("Config")["Labels"]?:emptyMap<String,Any?>()) as Map<String,Any?>
            check(revision=="baseline-unknown" || labels["org.opencontainers.image.revision"]==revision) { "Image revision differs from reviewed head" }
            report["resolvedImageId"]=imageData["Id"]; report["imageVersion"]=labels["org.opencontainers.image.version"]
            report["pluginHashes"]=listOf(metadata,direct).associate { it.fileName.toString() to ScanFixture.sha(it) }
            docker("network","create","--internal",network)
            val mounts=listOf("db","data","logs","plugins","plugindata").flatMap { listOf("--mount","type=bind,src=${root.resolve(it)},dst=/opt/gameyfin/$it") }+
                listOf("--mount","type=bind,src=${root.resolve("fixture/sources")},dst=/fixture,readonly")
            command(dockerPrefix+listOf("run","--pull","never","-d","--name",name,"--network",network,"--cpus","2","--cgroupns","private",
                "--memory","1536m","--memory-swap","1536m","--restart","no","--env-file",env.toString())+mounts+image)
            val ip=inspect().objectAt("NetworkSettings").objectAt("Networks").objectAt(network).getValue("IPAddress")
            base="http://$ip:8080"; metrics="http://$ip:8081/actuator/prometheus"; csrf=ready()
            val password=Base64.getUrlEncoder().withoutPadding().encodeToString(random(32))
            request("/connect/SetupEndpoint/registerSuperAdmin",mapOf("superAdminRegistration" to mapOf("username" to "fixture-admin","password" to password,"email" to "fixture@example.invalid")))
            fun login() { csrf=csrfIn(http("$base/login",form(mapOf("username" to "fixture-admin","password" to password,"_csrf" to csrf)),formBody=true).toString(Charsets.UTF_8))?:error("Login CSRF absent") }
            login()
            request("/connect/PluginEndpoint/enablePlugin",mapOf("pluginId" to (options["--metadata-plugin-id"]?:"org.gameyfin.plugins.metadata.steam")))
            request("/connect/PluginEndpoint/enablePlugin",mapOf("pluginId" to "org.gameyfin.plugins.download.direct"))
            docker("stop",name)
            docker("run","--pull","never","--rm","--network","none","--user","0","--entrypoint","java","--mount","type=bind,src=${root.resolve("db")},dst=/db",
                "--mount","type=bind,src=${root.resolve("fixture")},dst=/seed,readonly",image,"-cp",h2,"org.h2.tools.RunScript","-url","jdbc:h2:/db/gameyfin_db","-user","gfadmin","-password","gameyfin","-script","/seed/seed.sql")
            docker("run","--pull","never","--rm","--network","none","--user","0","--entrypoint","chown","--mount","type=bind,src=${root.resolve("db")},dst=/db",image,"-R","$uid:$gid","/db")
            docker("start",name); csrf=ready("/login"); login()
            val observed=command(dockerPrefix+optionProbe(name,uid,gid),10).text.lineSequence().filter { it.isNotBlank() }.toList()
            check(observed.size==1 && observed.single().split(Regex("\\s+"))==jvm.split(' ')) { "Fixture JVM did not receive exact controlled options" }
            profile["runtimeOptionEnvironmentVerified"]=true
            val phases=listOf("cold","repeat")+(if("--missing-root-fault" in options) listOf("missing-root-recovery") else emptyList())+(if("--interrupt-scan" in options) listOf("recovery") else emptyList())
            for(phase in phases) {
                if(phase=="missing-root-recovery") report["missingRootFault"]=verifyMissingRootFault(root,::request,::snapshot)
                if(phase=="recovery") {
                    request("/connect/LibraryEndpoint/triggerScan",mapOf("scanType" to "FULL","libraryIds" to listOf(27001,27002,27003,27004)))
                    val active=metric("gameyfin_scans_active",snapshot()); check(active>0) { "No active scan observed; interruption evidence invalid" }
                    docker("kill","--signal","KILL",name); docker("start",name); csrf=ready("/login"); login()
                    report["interruption"]=mapOf("kind" to "forced isolated process interruption","observedActiveLibraries" to active)
                }
                val initial=snapshot(); val completed=metric("gameyfin_scans_completed_total",initial); val failed=metric("gameyfin_scans_failed_total",initial)
                val started=now(); var peakHeap=0.0; var peakRss=0L; var timeouts=0; var longest=0.0; var done=0.0; var errors=0.0; var finished=false
                val pid=javaPid()
                request("/connect/LibraryEndpoint/triggerScan",mapOf("scanType" to "FULL","libraryIds" to listOf(27001,27002,27003,27004)))
                while(now()-started<180) {
                    peakRss=maxOf(peakRss,rss(pid)); val scrapeStarted=now()
                    val sample=try { snapshot() } catch(failure: java.net.http.HttpTimeoutException) {
                        timeouts++; longest=maxOf(longest,now()-scrapeStarted); if(timeouts>2) throw failure; continue
                    }
                    longest=maxOf(longest,now()-scrapeStarted); peakHeap=maxOf(peakHeap,metric("jvm_memory_used_bytes",sample,"area=\"heap\""))
                    done=metric("gameyfin_scans_completed_total",sample)-completed; errors=metric("gameyfin_scans_failed_total",sample)-failed
                    if(done+errors>=4) { finished=true; break }; Thread.sleep(500)
                }
                check(finished) { "Scan timed out" }; check(errors==0.0) { "Unexpected scan failure" }
                val games=rows(request("/connect/GameEndpoint/getAll",emptyMap<String,Any>()))
                check(games.size==132 && games.map { it.number("id") }.toSet()==expected.keys) { "Affected game identity set changed" }
                var defaultCount=0
                for(game in games) {
                    val fixture=expected.getValue(game.number("id")); val variants=game.rowsAt("variants")
                    defaultCount+=variants.count { it["default"]==true }
                    val pinned=variants.single { it.number("id")==fixture.number("variantId") }
                    val contents=pinned.rowsAt("contents").associateBy { it.number("id") }
                    check(contents.keys==setOf(fixture.number("requiredContentId"),fixture.number("optionalContentId")))
                    val required=contents.getValue(fixture.number("requiredContentId")); val optional=contents.getValue(fixture.number("optionalContentId"))
                    check(required["required"]==true && required.number("pathCount")==2L && optional["required"]==false && optional["defaultSelected"]==false)
                    if(revision!="baseline-unknown") { val defaults=variants.filter { it["default"]==true }; check(defaults.size==1 && defaults.single().number("id")==fixture.number("variantId") && defaults.single()["defaultLocked"]==true) }
                }
                scans+=mapOf("phase" to phase,"seconds" to now()-started,"completedLibraries" to done,"failedLibraries" to errors,"sampledPeakHeapBytes" to peakHeap,
                    "sampledPeakJvmRssBytes" to peakRss,"telemetryTimeouts" to timeouts,"longestTelemetryScrapeSeconds" to longest,"defaultVariants" to defaultCount)
            }
            val providers=rows(request("/connect/DownloadProviderEndpoint/getProviders",emptyMap<String,Any>())); check(providers.size==1)
            val selected=manifest.rowsAt("games").first(); val downloads=mutableListOf<Map<String,Any?>>()
            for(optional in listOf(false,true)) {
                val ids=listOf(selected.number("requiredContentId"))+(if(optional) listOf(selected.number("optionalContentId")) else emptyList())
                val query=form(mapOf("provider" to providers.single().getValue("key").toString(),"variantId" to selected.number("variantId").toString(),"contentIds" to ids.joinToString(","),"explicitSelection" to "true"))
                val route="/download/${selected.number("gameId")}"; val estimate=request("$route/estimate?$query").trim().toLong()
                val payload=http("$base$route?$query",limit=2*1024*1024)
                val memberPaths=selected.objectAt("requiredArchiveMembers")+(if(optional) selected.objectAt("optionalArchiveMembers") else emptyMap())
                val expectedMembers=memberPaths.mapValues { manifest.objectAt("sha256").getValue(it.value.toString()).toString() }
                val actual=linkedMapOf<String,String>(); var bytes=0L
                ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
                    while(true) { val entry=zip.nextEntry?:break; if(entry.isDirectory) continue
                        val content=zip.readNBytes(65537); check(content.size<=65536); bytes+=content.size; check(bytes<=131072)
                        check(actual.put(entry.name,ScanFixture.sha(content))==null) { "Duplicate ZIP member" }
                    }
                }
                check(actual==expectedMembers && estimate==bytes) { "Selected ZIP member bytes or estimate changed" }
                downloads+=mapOf("optionalSelected" to optional,"estimateBytes" to estimate,"archiveBytes" to payload.size,"memberHashes" to actual,"expectedMemberHashes" to expectedMembers,"selectedBytesMatch" to true)
            }
            report["downloads"]=downloads
            check(ScanFixture.inventory(root.resolve("fixture/sources"))==manifest.objectAt("sha256")); report["sourceHashesUnchanged"]=true
            if(options["--external-probe"]!=null) {
                val context=json.writeValueAsString(mapOf("backend" to name,"backendUrl" to base,"network" to network,"provider" to providers.single()["key"],
                    "fixtureManifest" to manifest,"sourceRevision" to revision,"proxyImage" to options["--proxy-image"]))
                // Built-in, bounded implementation; no arbitrary executable/class or persisted credentials.
                report["externalProbe"]=objectOf(ProxyExposureFixture.run(context,"fixture-admin",password,dockerPrefix))
            }
            if(idle>0) {
                val pid=javaPid()
                report["postTaskIdle"]=samplePostTaskIdle(idle,interval,{
                    val resident=rss(pid); val sample=snapshot(); requireScanQuiescence(sample)
                    val current=docker("exec",name,"cat","/sys/fs/cgroup/memory.current").toLong()
                    val stats=docker("exec",name,"cat","/sys/fs/cgroup/memory.stat").lineSequence().map { it.split(' ') }.associate { it[0] to it[1].toLong() }
                    mapOf("heapUsedBytes" to metric("jvm_memory_used_bytes",sample,"area=\"heap\""),"jvmRssBytes" to resident,
                        "containerCgroupCurrentBytes" to current,"containerAnonymousBytes" to stats.getValue("anon"),"containerFileCacheBytes" to stats.getValue("file"))
                })
            }
            val final=inspect(); report["restartCount"]=final["RestartCount"]; report["oomKilled"]=final.objectAt("State")["OOMKilled"]
            report["health"]=objectOf(http(metrics.replace("prometheus","health"),timeout=10,meter=true).toString(Charsets.UTF_8))["status"]
            val logs=docker("logs",name).lines(); val error=Regex("\\bERROR\\b")
            report["errorLogLineCount"]=logs.count { error.containsMatchIn(it) }
            report["oomErrorLineCount"]=logs.count { Regex("\\bOutOfMemoryError(?::|\\s*$)").containsMatchIn(it) }
            report["offlineMetadataMissErrorLineCount"]=logs.count { "No results found for originalIds: {}" in it && error.containsMatchIn(it) }
            val missing=if("--missing-root-fault" in options) logs.count { "Error during full scan for library 27001 (OTHER: NoSuchFileException)" in it && error.containsMatchIn(it) } else 0
            report["expectedMissingRootErrorLineCount"]=missing
            if("--missing-root-fault" in options) check(missing==1)
            report["unexpectedErrorLogLineCount"]=(report["errorLogLineCount"] as Int)-(report["offlineMetadataMissErrorLineCount"] as Int)-missing
            val exception=Regex("\\b(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)\\b")
            report["loggedExceptionClasses"]=exception.findAll(logs.joinToString("\n")).map { it.value }.toSortedSet().take(30)
            report["closedDatabaseErrorLineCount"]=logs.count { "database is already closed" in it.lowercase() || "database has been closed" in it.lowercase() }
            check(report["oomKilled"]==false && report["oomErrorLineCount"]==0 && report["health"]=="UP") { "Fixture unhealthy after scans" }
            report["remainingGaps"]=listOf("production acceptance")+(if("--interrupt-scan" !in options) listOf("process interruption recovery") else emptyList())+
                (if("--missing-root-fault" !in options) listOf("filesystem failure retention/recovery") else emptyList())
            writeReport()
        } catch(failure: Exception) {
            report["failureType"]=failure.javaClass.simpleName
            val state=command(dockerPrefix+listOf("inspect",name),15,false)
            if(state.code==0) {
                val inspected=rows(state.text).single().objectAt("State")
                report["failureContainerState"]=listOf("Status","Running","OOMKilled","ExitCode").associateWith { inspected[it] }
                val logs=command(dockerPrefix+listOf("logs","--tail","200",name),15,false).text.lines()
                report["failureLogCounts"]=listOf("ERROR","OutOfMemoryError","SQLException","HikariPool","TimeoutException").associateWith { kind -> logs.count { kind in it } }
                report["offlineMetadataMissLogCount"]=logs.count { "No results found for originalIds: {}" in it }
                report["failureExceptionClasses"]=Regex("\\b(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)\\b").findAll(logs.joinToString("\n")).map { it.value }.toSortedSet().take(30)
                command(dockerPrefix+listOf("kill","--signal","QUIT",name),15,false); Thread.sleep(1000)
                report["failureThreadFrames"]=command(dockerPrefix+listOf("logs","--tail","3000",name),15,false).text.lines().filter {
                    Regex("\\s+(?:java.lang.Thread.State:|at (?:org.gameyfin\\.|org.h2\\.|com.zaxxer.hikari\\.|io.micrometer\\.))").containsMatchIn(it) }.map { it.trim() }.take(250)
            }
            writeReport(); throw IllegalStateException("Synthetic image rehearsal failed (${failure.javaClass.simpleName}); see sanitized report")
        } finally {
            command(dockerPrefix+listOf("rm","-f",name),60,false); command(dockerPrefix+listOf("network","rm",network),60,false)
            val container=command(dockerPrefix+listOf("ps","-a","--filter","name=^/$name$","--format","{{.Names}}"),60,false)
            val networks=command(dockerPrefix+listOf("network","ls","--filter","name=^$network$","--format","{{.Name}}"),60,false)
            check(container.code==0 && networks.code==0 && container.text.isEmpty() && networks.text.isEmpty()) { "Exact synthetic cleanup unverified" }
            println("Private synthetic test leaf (remove after inspection): $root")
        }
    }
    private object HexFormatCompat { fun hex(bytes: ByteArray)=java.util.HexFormat.of().formatHex(bytes) }
}
