package org.gameyfin.app.core.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.gameyfin.app.libraries.enums.ScanType
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals

class ScanMetricsTest {
    @Test
    fun `database failures use a bounded metric category without exception text`() {
        val registry = SimpleMeterRegistry()
        val metrics = ScanMetrics(registry)
        val error = IllegalStateException("private library path", SQLException("database unavailable"))

        metrics.recordScanStarted(ScanType.FULL)
        metrics.recordScanFailed(ScanType.FULL, 1200, ScanMetrics.FailureKind.from(error))

        assertEquals(ScanMetrics.FailureKind.DATABASE, ScanMetrics.FailureKind.from(error))
        assertEquals(1.0, registry.find("gameyfin.scans.failures.by.kind")
            .tags("type", "full", "kind", "database").counter()?.count())
        assertEquals(1.0, registry.find("gameyfin.scans.failed")
            .tag("type", "full").counter()?.count())
        assertEquals(0.0, registry.find("gameyfin.scans.active").gauge()?.value())
    }

    @Test
    fun `non database failures use the other category`() {
        assertEquals(ScanMetrics.FailureKind.OTHER, ScanMetrics.FailureKind.from(IllegalStateException("scan failed")))
    }
}
