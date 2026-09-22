package org.gameyfin.app.games

import jakarta.persistence.EntityManager
import org.gameyfin.app.games.entities.Company
import org.gameyfin.app.games.entities.CompanyType
import org.gameyfin.app.games.repositories.CompanyRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
@Import(CompanyService::class, CompanyInsertService::class)
class CompanyServiceConcurrencyTest @Autowired constructor(
    private val companyService: CompanyService,
    private val companyRepository: CompanyRepository,
    private val entityManager: EntityManager
) {
    @Test
    fun `new company is managed in the caller transaction`() {
        val company = companyService.createOrGet(Company(
            name = "Managed-${UUID.randomUUID()}", type = CompanyType.PUBLISHER
        ))
        assertTrue(entityManager.contains(company))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent metadata updates reuse one committed company`() {
        val name = "Concurrent-${UUID.randomUUID()}"
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val results = (1..6).map {
                executor.submit(Callable {
                    start.await()
                    companyService.createOrGet(Company(name = name, type = CompanyType.DEVELOPER)).id
                })
            }
            start.countDown()
            val ids = results.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, ids.toSet().size)
            assertEquals(ids.first(), companyRepository.findByNameAndType(name, CompanyType.DEVELOPER)?.id)
            assertEquals(1, companyRepository.findAll().count { it.name == name && it.type == CompanyType.DEVELOPER })
        } finally {
            executor.shutdownNow()
        }
    }
}
