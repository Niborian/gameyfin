package org.gameyfin.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Pure fault/sampler contracts migrated from the removed Python harness; no Docker/application. */
class ImageScanContractsTest {
    @Test fun `idle requires active and draining from the same mandatory sample`() {
        for((active,draining) in listOf(1 to 0,0 to 1,1 to 1)) assertFails {
            ImageScanRehearsal.requireScanQuiescence("gameyfin_scans_active $active\ngameyfin_scans_draining $draining\n")
        }
        assertFails { ImageScanRehearsal.requireScanQuiescence("gameyfin_scans_active 0\n") }
        ImageScanRehearsal.requireScanQuiescence("gameyfin_scans_active 0\ngameyfin_scans_draining 0\n")
    }
    @Test fun `natural idle windows retain memory domains and exact final checkpoint`() {
        for(duration in listOf(31,300,900)) {
            var elapsed=0.0
            val result=ImageScanRehearsal.samplePostTaskIdle(duration,15,
                { mapOf("heapUsedBytes" to 100,"jvmRssBytes" to 200,"containerCgroupCurrentBytes" to 300,"containerAnonymousBytes" to 180,"containerFileCacheBytes" to 100) },
                { elapsed },{ elapsed+=it })
            assertEquals(duration.toDouble(),result["observedSeconds"])
            @Suppress("UNCHECKED_CAST") val samples=result["samples"] as List<Map<String,Any?>>
            assertEquals(0.0,samples.first()["elapsedSeconds"])
            assertEquals(duration.toDouble(),samples.last()["elapsedSeconds"])
            assertEquals(100,samples.last()["heapUsedBytes"])
            assertEquals(200,samples.last()["jvmRssBytes"])
            assertEquals(300,samples.last()["containerCgroupCurrentBytes"])
            assertEquals(true,result["targetsAreNotLimits"])
        }
    }
    @Test fun `invalid idle windows and measurement failures are not fabricated`() {
        for((duration,interval) in listOf(0 to 15,901 to 15,300 to 0,5 to 15))
            assertFails { ImageScanRehearsal.samplePostTaskIdle(duration,interval,{ emptyMap() }) }
        assertFails { ImageScanRehearsal.samplePostTaskIdle(300,15,{ error("metric unavailable") }) }
    }
    @Test fun `JVM option probe uses exact owner and only fixed environment key`() {
        val command=ImageScanRehearsal.optionProbe("owned-fixture","1337","1337")
        assertEquals(listOf("exec","--user","1337:1337","owned-fixture","sh","-c"),command.take(6))
        assertTrue(command.last().contains("sed -n \"s/^JDK_JAVA_OPTIONS=//p\""))
        assertFalse(command.last().contains("APP_KEY"))
        assertFalse(command.contains("--privileged"))
    }
    private fun fault(root: Path, completed: Int=0, failed: Int=1, changed: Boolean=false, requestError: Boolean=false, timeout: Double=180.0): Map<String,Any?> {
        val source=Files.createDirectories(root.resolve("fixture/sources/lib1"))
        val sentinel=source.resolve("original.bin"); Files.writeString(sentinel,"unchanged synthetic source")
        var reads=0; var snapshots=0
        try {
            return ImageScanRehearsal.verifyMissingRootFault(root,{ path,body ->
                if(path.endsWith("triggerScan")) {
                    assertEquals(mapOf("scanType" to "FULL","libraryIds" to listOf(27001)),body)
                    assertFalse(Files.exists(source)); assertTrue(Files.isDirectory(root.resolve("fixture/fault-lib1")))
                    if(requestError) error("synthetic request failure")
                    "null"
                } else { reads++; "[{\"id\":1,\"variants\":[{\"id\":${if(changed && reads>1) 3 else 2}}]}]" }
            },{
                snapshots++
                "gameyfin_scans_completed_total ${if(snapshots==1) 0 else completed}\ngameyfin_scans_failed_total ${if(snapshots==1) 0 else failed}\ngameyfin_scans_active 0\n"
            },timeout)
        } finally {
            assertTrue(Files.isDirectory(source)); assertFalse(Files.exists(root.resolve("fixture/fault-lib1")))
            assertEquals("unchanged synthetic source",Files.readString(sentinel))
        }
    }
    @Test fun `failed scan preserves records and restores synthetic source`(@TempDir root: Path) {
        assertEquals(true,fault(root)["recordsUnchanged"])
    }
    @Test fun `success is not failed scan evidence`(@TempDir root: Path) { assertFails { fault(root,completed=1,failed=0) } }
    @Test fun `changed records fail and source is restored`(@TempDir root: Path) { assertFails { fault(root,changed=true) } }
    @Test fun `request failure restores source`(@TempDir root: Path) { assertFails { fault(root,requestError=true) } }
    @Test fun `unobserved fault fails and restores source`(@TempDir root: Path) { assertFails { fault(root,timeout=0.0) } }
}
