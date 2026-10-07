package org.gameyfin.app.core.download.files

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.test.*

class DownloadPathLeasesTest {
    @Test
    fun `download lease blocks parent mirror until EOF or closure`() {
        val leases = DownloadPathLeases()
        val root = Path.of("mirror")
        val stream = leases.guard(ByteArrayInputStream(byteArrayOf(1)), leases.acquire(listOf(root.resolve("file"))))
        assertFailsWith<IllegalArgumentException> { leases.whenUnused(root) {} }
        assertEquals(1, stream.read())
        assertFailsWith<IllegalArgumentException> { leases.whenUnused(root) {} }
        assertEquals(-1, stream.read())
        leases.whenUnused(root) {}
        stream.close()
        val second = leases.guard(ByteArrayInputStream(byteArrayOf(2)), leases.acquire(listOf(root)))
        second.close()
        leases.whenUnused(root.resolve("file")) {}
    }
}
