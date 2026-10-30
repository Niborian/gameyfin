package org.gameyfin.app.games.variants

import io.mockk.*
import jakarta.persistence.EntityManager
import org.gameyfin.app.core.Role
import org.gameyfin.app.games.dto.*
import org.gameyfin.app.games.entities.VariantDetailsSource
import org.gameyfin.app.games.entities.VariantStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Actual Flyway/H2 persistence and service transactions; detector is mocked and never opens a path. */
@DataJpaTest(properties = ["spring.jpa.show-sql=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Import(VariantDetailsService::class, VariantDetailsServiceJpaTest.DetectorConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VariantDetailsServiceJpaTest @Autowired constructor(
    private val em: EntityManager, manager: PlatformTransactionManager,
    private val service: VariantDetailsService, private val detector: VariantLocalVersionDetector,
    private val detailsRepository: org.gameyfin.app.games.repositories.VariantDetailsRepository,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class DetectorConfig {
        @Bean fun detector(): VariantLocalVersionDetector = mockk()
    }
    private val tx = TransactionTemplate(manager)
    private var gameId = 0L
    private var variantId = 0L
    private lateinit var path: String
    private fun detected(build: String = "200") = VariantLocalVersionPreviewDto(
        listOf(VariantLocalVersionSuggestionDto("STEAM_APP_MANIFEST", null, build,
            "Synthetic exact ID evidence", "steamapps/appmanifest_42.acf")), "Synthetic preview", false)

    @BeforeEach fun fixture() {
        val id = ids.getAndAdd(10)
        gameId = id + 1; variantId = id + 2
        path = "/synthetic/variant-details-$id/game/variant"
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "synthetic-admin", null, listOf(SimpleGrantedAuthority(Role.Names.ADMIN)))
        clearMocks(detector)
        every { detector.preview(any(), any(), any(), any(), any(), any()) } returns detected()
        tx.executeWithoutResult {
            // Native fixture inserts avoid unrelated catalog entity-event listeners; services/queries are real.
            fun sql(value: String) { em.createNativeQuery(value).executeUpdate() }
            sql("INSERT INTO LIBRARY(ID,CREATED_AT,NAME,UPDATED_AT,STORAGE_MODE) VALUES($id,CURRENT_TIMESTAMP,'Synthetic',CURRENT_TIMESTAMP,'DIRECT')")
            sql("INSERT INTO DIRECTORY_MAPPING(ID,EXTERNAL_PATH,INTERNAL_PATH) VALUES($id,'/synthetic/variant-details-$id','/synthetic/variant-details-$id')")
            sql("INSERT INTO LIBRARY_DIRECTORIES(LIBRARY_ID,DIRECTORIES_ID) VALUES($id,$id)")
            sql("INSERT INTO GAME(ID,CREATED_AT,TITLE,UPDATED_AT,PATH,FILE_SIZE,LIBRARY_ID,DOWNLOAD_COUNT,MATCH_CONFIRMED) VALUES($gameId,CURRENT_TIMESTAMP,'Synthetic',CURRENT_TIMESTAMP,'/synthetic/variant-details-$id/game',7,$id,0,TRUE)")
            sql("INSERT INTO GAME_VARIANT(ID,NAME,VERSION,PATH,FILE_SIZE,GAME_ID,IS_DEFAULT,DEFAULT_LOCKED,IS_LATEST_FOR_VARIANT,SCAN_MANAGED,LINK_STATUS,STEAM_APP_ID,STEAM_APP_ID_MANUAL_OVERRIDE,LOCAL_BUILD_VERSION) VALUES($variantId,'Normal','legacy-label','$path',7,$gameId,TRUE,TRUE,TRUE,FALSE,'DIRECT','42',FALSE,'legacy-build')")
            sql("INSERT INTO VARIANT_CONTENT(ID,NAME,PATH,FILE_SIZE,TYPE,REQUIRED,DEFAULT_SELECTED,VARIANT_ID) VALUES(${id+3},'Base','$path/base.bin',7,'BASE',TRUE,TRUE,$variantId)")
            sql("INSERT INTO VARIANT_CONTENT_PATHS(VARIANT_CONTENT_ID,PATH_INDEX,PATH) VALUES(${id+3},0,'$path/base.bin')")
        }
    }
    @AfterEach fun cleanup() { SecurityContextHolder.clearContext() }

    @Test fun `player projection exposes saved labels only and hides retired or mismatched variants`() {
        val before = catalog()
        val edition = "Survivors of the Void + Seekers of the Storm + Alloyed Collective + Hallowed Concepts"
        service.update(gameId, variantId, manual().copy(libraryVersion = "1.5.0", edition = edition))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "synthetic-player", null, listOf(SimpleGrantedAuthority(Role.Names.USER)))
        val endpoint = VariantDisplayEndpoint(detailsRepository)
        assertEquals(listOf(VariantDisplayDto(variantId, "1.5.0", edition)), endpoint.getForGame(gameId))
        assertTrue(endpoint.getForGame(gameId + 999).isEmpty())
        assertEquals(setOf("variantId", "libraryVersion", "edition"), VariantDisplayDto::class.java.declaredFields.map { it.name }.toSet())
        assertEquals(before, catalog())
        tx.execute { em.createNativeQuery("UPDATE GAME_VARIANT SET RETIREMENT_STATE='ARCHIVED' WHERE ID=$variantId").executeUpdate() }
        assertTrue(endpoint.getForGame(gameId).isEmpty())
        tx.execute { em.createNativeQuery("UPDATE GAME_VARIANT SET RETIREMENT_STATE='ACTIVE' WHERE ID=$variantId").executeUpdate()
            em.createNativeQuery("UPDATE VARIANT_DETAILS SET GAME_ID=${gameId + 999} WHERE VARIANT_ID=$variantId").executeUpdate() }
        assertTrue(endpoint.getForGame(gameId).isEmpty())
    }
    private fun manual(revision: String = "0", protect: Boolean = true) = UpdateVariantDetailsRequestDto(
        revision, VariantStore.STEAM, "42", "Deluxe", "1.2", "123", protect)
    private fun count(): Long = tx.execute {
        (em.createNativeQuery("SELECT COUNT(*) FROM VARIANT_DETAILS WHERE VARIANT_ID=$variantId").singleResult as Number).toLong()
    }!!
    private fun catalog(): List<List<Any?>> = tx.execute {
        fun rows(sql: String): List<List<Any?>> = em.createNativeQuery(sql).resultList.map { value ->
            (value as Array<*>).toList()
        }
        rows("SELECT ID,GAME_ID,NAME,VERSION,PATH,FILE_SIZE,IS_DEFAULT,DEFAULT_LOCKED,IS_LATEST_FOR_VARIANT,SCAN_MANAGED,STEAM_APP_ID,STEAM_APP_ID_MANUAL_OVERRIDE,LOCAL_BUILD_VERSION FROM GAME_VARIANT WHERE ID=$variantId") +
            rows("SELECT ID,VARIANT_ID,TYPE,NAME,PATH,FILE_SIZE,REQUIRED,DEFAULT_SELECTED FROM VARIANT_CONTENT WHERE VARIANT_ID=$variantId") +
            rows("SELECT VARIANT_CONTENT_ID,PATH_INDEX,PATH FROM VARIANT_CONTENT_PATHS WHERE VARIANT_CONTENT_ID=${gameId+2}")
    }!!

    @Test fun `manual save reads back and stale revision cannot overwrite`() {
        val before = catalog()
        assertFailsWith<IllegalArgumentException> { service.update(gameId+99, variantId, manual()) }
        assertEquals(0L, count())
        val saved = service.update(gameId, variantId, manual())
        val read = service.getDetails(gameId, variantId)
        assertEquals("1", saved.detailsRevision)
        // H2 TIMESTAMP(6) rounds Instant nanoseconds on persistence; all other fields must be identical.
        assertEquals(saved.copy(detailsObservedAt = read.detailsObservedAt), read)
        assertNotNull(read.detailsObservedAt)
        assertEquals(VariantDetailsSource.MANUAL, read.detailsSource)
        assertEquals("Deluxe", read.edition)
        assertEquals("1.2", read.libraryVersion)
        assertEquals("123", read.buildId)
        assertFailsWith<IllegalArgumentException> { service.update(gameId, variantId, manual("0")) }
        assertEquals(read, service.getDetails(gameId, variantId))
        assertEquals(before, catalog())
    }
    @Test fun `manual protection blocks explicit detected apply`() {
        service.update(gameId, variantId, manual())
        val before = service.getDetails(gameId, variantId)
        val preview = service.preview(gameId, variantId)
        assertFailsWith<IllegalArgumentException> {
            service.applyPreview(gameId, variantId, ApplyVariantDetailsPreviewRequestDto("1", preview.previewDigest, 0))
        }
        assertEquals(before, service.getDetails(gameId, variantId))
        assertEquals(1L, count())
    }
    @Test fun `preview never writes and changed local evidence rejects old digest`() {
        val before = catalog()
        val preview = service.preview(gameId, variantId)
        assertEquals("0", preview.current.detailsRevision)
        assertNull(preview.current.libraryVersion) // legacy build is deliberately not relabeled as a release version
        assertEquals(0L, count())
        every { detector.preview(any(), any(), any(), any(), any(), any()) } returns detected("201")
        assertFailsWith<IllegalArgumentException> {
            service.applyPreview(gameId, variantId, ApplyVariantDetailsPreviewRequestDto("0", preview.previewDigest, 0))
        }
        assertEquals(0L, count())
        assertEquals(before, catalog())
    }
    @Test fun `explicit successful apply only creates details and leaves catalog untouched`() {
        val before = catalog()
        val preview = service.preview(gameId, variantId)
        val applied = service.applyPreview(gameId, variantId, ApplyVariantDetailsPreviewRequestDto("0", preview.previewDigest, 0))
        assertEquals("1", applied.detailsRevision)
        assertEquals("200", applied.buildId)
        assertNull(applied.libraryVersion)
        assertEquals(VariantDetailsSource.DETECTED, applied.detailsSource)
        assertTrue(requireNotNull(applied.detailsEvidence).contains("steamapps/appmanifest_42.acf"))
        assertFalse(applied.detailsManualOverride)
        assertEquals(1L, count())
        assertEquals(before, catalog())
    }
    companion object { private val ids = AtomicLong(780000) }
}
