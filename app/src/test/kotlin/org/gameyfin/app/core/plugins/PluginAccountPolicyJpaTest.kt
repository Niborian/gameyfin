package org.gameyfin.app.core.plugins

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import jakarta.persistence.EntityManager
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.security.EncryptionUtils
import org.gameyfin.app.users.entities.User
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.*

@DataJpaTest(properties = ["spring.jpa.show-sql=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Import(PluginAccountPolicy::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PluginAccountPolicyJpaTest @Autowired constructor(
    private val em: EntityManager, manager: PlatformTransactionManager, private val policy: PluginAccountPolicy,
) {
    private val tx = TransactionTemplate(manager)
    @BeforeEach fun syntheticEncryptionKey() {
        mockkObject(EncryptionUtils)
        every { EncryptionUtils.getAppKey() } returns "MDEyMzQ1Njc4OWFiY2RlZg=="
    }
    @AfterEach fun releaseEncryptionMock() { unmockkObject(EncryptionUtils) }
    private fun user(oidc: String? = null): User = tx.execute {
        val name = UUID.randomUUID().toString()
        User(username = name, email = "$name@example.invalid", enabled = true, oidcProviderId = oidc,
            roles = listOf(Role.ADMIN)).also { em.persist(it); em.flush() }
    }!!
    @Test fun `fresh policy observes committed demotion and disable rather than captured entity`() {
        val user = user()
        val auth = UsernamePasswordAuthenticationToken(user.username, null, listOf(SimpleGrantedAuthority(Role.Names.ADMIN)))
        val captured = assertNotNull(policy.resolve(auth))
        assertTrue(captured.administrator)
        tx.executeWithoutResult {
            val current = em.find(User::class.java, user.id)
            current.roles = listOf(Role.USER)
            current.enabled = false
        }
        val fresh = assertNotNull(policy.current(captured.id))
        assertFalse(fresh.administrator)
        assertFalse(fresh.enabled)
        assertTrue(captured.administrator)
    }
    @Test fun `OIDC subject lookup does not fall back to matching username`() {
        val oidcSubject = UUID.randomUUID().toString()
        val user = user(oidcSubject)
        val principal = mockk<OidcUser> {
            every { subject } returns oidcSubject
            every { name } returns user.username
        }
        val auth = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        assertEquals(user.id, assertNotNull(policy.resolve(auth)).id)
        val unknown = mockk<OidcUser> {
            every { subject } returns "missing-subject"
            every { name } returns user.username
        }
        assertNull(policy.resolve(UsernamePasswordAuthenticationToken(unknown, null, emptyList())))
    }
    @Test fun `deleted current numeric identity does not adopt replacement username`() {
        val user = user()
        val id = requireNotNull(user.id)
        // Exercise fresh policy lookup after deletion, not unrelated application event listeners.
        tx.executeWithoutResult {
            em.createNativeQuery("DELETE FROM USER_ROLES WHERE USER_ID = :id").setParameter("id", id).executeUpdate()
            em.createNativeQuery("DELETE FROM USERS WHERE ID = :id").setParameter("id", id).executeUpdate()
        }
        assertNull(policy.current(id))
    }
}
