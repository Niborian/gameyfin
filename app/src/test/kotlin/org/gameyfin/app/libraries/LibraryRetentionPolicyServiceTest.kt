package org.gameyfin.app.libraries

import io.mockk.*
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.libraries.dto.UpdateLibraryRetentionPolicyRequestDto
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.libraries.entities.LibraryRetentionPolicy
import org.gameyfin.app.libraries.entities.LibraryRetentionPolicyChange
import org.gameyfin.app.libraries.entities.LibraryRetentionPolicyMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.repository.findByIdOrNull
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LibraryRetentionPolicyServiceTest {
    private lateinit var libraries: LibraryRepository
    private lateinit var policies: LibraryRetentionPolicyRepository
    private lateinit var changes: LibraryRetentionPolicyChangeRepository
    private lateinit var service: LibraryRetentionPolicyService
    private val library = Library(id = 7L, name = "Games")

    @BeforeEach fun setup() {
        libraries = mockk(); policies = mockk(); changes = mockk()
        service = LibraryRetentionPolicyService(libraries, policies, changes)
        every { libraries.findById(7L) } returns Optional.of(library)
        mockkStatic("org.gameyfin.app.core.security.SecurityUtilsKt")
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null
    }

    @AfterEach fun cleanup() = unmockkAll()

    @Test fun `existing library defaults to advisory keep all without creating a record`() {
        every { policies.findByLibraryId(7L) } returns null

        val policy = service.get(7L)

        assertEquals(LibraryRetentionPolicyMode.KEEP_ALL, policy.mode)
        assertNull(policy.updatedAt)
        verify(exactly = 0) { policies.save(any()) }
        verify(exactly = 0) { changes.save(any()) }
    }

    @Test fun `latest count policy stores only its relevant parameter and records audit`() {
        every { policies.findByLibraryId(7L) } returns null
        every { policies.save(any()) } answers { firstArg<LibraryRetentionPolicy>().also { it.id = 3L } }
        every { changes.save(any()) } answers { firstArg<LibraryRetentionPolicyChange>().also { it.id = 4L } }

        val result = service.update(7L, UpdateLibraryRetentionPolicyRequestDto(LibraryRetentionPolicyMode.KEEP_LATEST_N, keepLatestCount = 3))

        assertEquals(LibraryRetentionPolicyMode.KEEP_LATEST_N, result.mode)
        assertEquals(3, result.keepLatestCount)
        assertNull(result.gracePeriodDays)
        verify(exactly = 1) { changes.save(match { it.previousMode == LibraryRetentionPolicyMode.KEEP_ALL && it.newKeepLatestCount == 3 }) }
    }

    @Test fun `policy validation rejects irrelevant or missing parameters before persistence`() {
        assertFailsWith<IllegalArgumentException> {
            service.update(7L, UpdateLibraryRetentionPolicyRequestDto(LibraryRetentionPolicyMode.KEEP_ALL, keepLatestCount = 1))
        }
        assertFailsWith<IllegalArgumentException> {
            service.update(7L, UpdateLibraryRetentionPolicyRequestDto(LibraryRetentionPolicyMode.GRACE_PERIOD))
        }
        verify(exactly = 0) { policies.save(any()) }
        verify(exactly = 0) { changes.save(any()) }
    }

    @Test fun `retention policy endpoint is restricted to administrators`() {
        val roles = requireNotNull(LibraryRetentionPolicyEndpoint::class.java.getAnnotation(RolesAllowed::class.java))

        assertEquals(listOf(Role.Names.ADMIN), roles.value.toList())
    }
}
