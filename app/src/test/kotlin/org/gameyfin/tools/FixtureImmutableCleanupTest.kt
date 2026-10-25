package org.gameyfin.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
class FixtureImmutableCleanupTest {
    @Test fun `immutable fixture cleanup waits for workers and never follows external links or changes file permissions`() {
        val root = FixtureRuntime.temporary("gameyfin-cleanup-")
        val outside = FixtureRuntime.temporary("gameyfin-outside-")
        val owned = FixtureContainers(root)
        try {
            val data = Files.createDirectories(root.resolve("cache/snapshot/data"))
            Files.write(data.resolve("manifest.bin"), byteArrayOf(1))
            val original = Files.write(outside.resolve("original.bin"), byteArrayOf(2))
            Files.createLink(root.resolve("source.bin"), original)
            Files.setPosixFilePermissions(original, PosixFilePermissions.fromString("r--------"))
            Files.createSymbolicLink(root.resolve("cache/snapshot/external"), outside)
            for (directory in listOf(data, data.parent, data.parent.parent)) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
            assertFailsWith<IllegalStateException> { owned.closeImmutableFixture { error("Worker exit unknown") } }
            assertTrue(Files.exists(data.resolve("manifest.bin"), NOFOLLOW_LINKS))
            assertEquals(PosixFilePermissions.fromString("r-x------"), Files.getPosixFilePermissions(data))
            val outsidePermissions = Files.getPosixFilePermissions(outside)
            owned.closeImmutableFixture { /* No child processes were created in this pure fixture. */ }
            assertFalse(Files.exists(root, NOFOLLOW_LINKS))
            assertContentEquals(byteArrayOf(2), Files.readAllBytes(original))
            assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(original))
            assertEquals(outsidePermissions, Files.getPosixFilePermissions(outside))
        } finally {
            if (Files.exists(root, NOFOLLOW_LINKS)) owned.closeImmutableFixture { }
            FixtureRuntime.deleteOwned(outside)
        }
    }
}
