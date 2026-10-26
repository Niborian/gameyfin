plugins { kotlin("jvm") }

// The native-access class path contains only this worker and Kotlin's runtime,
// never Spring, PF4J or application plugins.
val workerJar by tasks.registering(Jar::class) {
    archiveFileName.set("gameyfin-snapshot-worker.jar")
    manifest { attributes("Main-Class" to "org.gameyfin.snapshot.SnapshotWorker") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
configurations.create("snapshotWorkerDistribution") {
    isCanBeResolved = false
    isCanBeConsumed = true
    outgoing.artifact(workerJar)
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${rootProject.extra["junitVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.2")
}
tasks.test {
    useJUnitPlatform()
    // This dedicated minimal worker test JVM contains no Spring/PF4J/application.
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
}

tasks.register("verifyNativeEvidence") {
    group = "verification"
    dependsOn(tasks.test)
    val report = layout.buildDirectory.file("test-results/test/TEST-org.gameyfin.snapshot.LinuxSnapshotBoundaryTest.xml")
    doLast {
        require(System.getProperty("os.name") == "Linux") { "Native evidence requires Linux execution" }
        val file = report.get().asFile
        require(file.isFile) { "Missing Linux snapshot boundary evidence" }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val suite = factory.newDocumentBuilder().parse(file).documentElement
        require(suite.tagName == "testsuite" && suite.getAttribute("name") == "org.gameyfin.snapshot.LinuxSnapshotBoundaryTest" && suite.getAttribute("tests").toInt() > 0 &&
            listOf("failures", "errors", "skipped").all { suite.getAttribute(it).toInt() == 0 }) {
            "Linux descriptor checks must execute without skips/failures"
        }
    }
}
