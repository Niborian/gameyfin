package org.gameyfin.app.games.variants

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.core.security.isAdmin
import org.gameyfin.app.games.dto.*
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.VariantDetailsRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** Metadata-only editor. No preview writes, network calls, path/content/default/Steam-field changes. */
@Service
class VariantDetailsService(
    private val repository: VariantDetailsRepository,
    private val detector: VariantLocalVersionDetector,
    manager: PlatformTransactionManager,
) {
    @PersistenceContext private lateinit var em: EntityManager
    private val read = TransactionTemplate(manager).apply {
        propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }
    private val write = TransactionTemplate(manager).apply {
        propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun getDetails(gameId: Long, variantId: Long): VariantDetailsDto {
        administrator()
        return details(variant(gameId,variantId))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun update(gameId: Long, variantId: Long, request: UpdateVariantDetailsRequestDto): VariantDetailsDto {
        val actor=administrator()
        val expected=revision(request.expectedRevision)
        val product=text(request.storeProductId,128)
        validateProduct(request.store,product)
        val edition=text(request.edition,128); val version=text(request.libraryVersion,128); val build=text(request.buildId,128)
        val variant=variant(gameId,variantId,locked=true)
        val current=details(variant)
        require(current.detailsRevision==expected.toString()) { "Variant details changed; reload before saving" }
        val next=Math.addExact(expected,1)
        val row=repository.findByIdOrNull(variantId)?:VariantDetails(variantId,gameId)
        require(row.gameId==gameId)
        row.store=request.store; row.storeProductId=product; row.edition=edition
        row.libraryVersion=version; row.buildId=build; row.manualOverride=request.manualOverride
        row.revision=next; row.source=VariantDetailsSource.MANUAL
        row.evidence="Administrator-entered metadata"; row.observedAt=Instant.now(); row.actor=actor
        repository.saveAndFlush(row)
        return row.toDto()
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun preview(gameId: Long, variantId: Long): VariantDetailsPreviewDto {
        administrator()
        val snapshot=requireNotNull(read.execute { snapshot(variant(gameId,variantId)) })
        return previewOf(snapshot)
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun applyPreview(gameId: Long, variantId: Long, request: ApplyVariantDetailsPreviewRequestDto): VariantDetailsDto {
        val actor=administrator()
        val expected=revision(request.expectedRevision)
        require(request.previewDigest.matches(Regex("[a-f0-9]{64}"))) { "Invalid preview digest" }
        val observed=requireNotNull(read.execute { snapshot(variant(gameId,variantId)) })
        // Filesystem work runs with no database transaction or pessimistic lock held.
        val preview=previewOf(observed)
        require(preview.current.detailsRevision==expected.toString() && preview.previewDigest==request.previewDigest) {
            "Preview or variant details changed; preview again before applying"
        }
        require(!preview.current.detailsManualOverride) { "Manual metadata is protected; explicitly clear protection and save first" }
        require(request.suggestionIndex in preview.suggestions.indices) { "Select a current preview suggestion" }
        val suggestion=preview.suggestions[request.suggestionIndex]
        val version=text(suggestion.version,128); val build=text(suggestion.build,128)
        require(version!=null || build!=null) { "Suggestion has no version or build to apply" }
        require(suggestion.source.isNotBlank() && !Path.of(suggestion.source).isAbsolute && ".." !in suggestion.source.split('/','\\'))
        val evidence="${suggestion.kind}: ${suggestion.source}; ${suggestion.message}"
        require(evidence.length<=2048 && evidence.none { it.isISOControl() }) { "Evidence exceeds bounded metadata format" }
        return requireNotNull(write.execute {
            administrator()
            val variant=variant(gameId,variantId,locked=true)
            require(snapshot(variant)==observed) { "Variant details or location changed while detecting; preview again" }
            val next=Math.addExact(expected,1)
            val row=repository.findByIdOrNull(variantId)?:VariantDetails(variantId,gameId,
                store=preview.current.store,storeProductId=preview.current.storeProductId,edition=preview.current.edition,
                libraryVersion=preview.current.libraryVersion,buildId=preview.current.buildId)
            require(row.gameId==gameId)
            // Store identity, edition and unknown components remain unchanged. Legacy variant
            // version/localBuildVersion/steamAppId are never assigned by this service.
            if(version!=null) row.libraryVersion=version
            if(build!=null) row.buildId=build
            row.revision=next; row.source=VariantDetailsSource.DETECTED; row.evidence=evidence
            row.observedAt=Instant.now(); row.actor=actor
            repository.saveAndFlush(row)
            row.toDto()
        })
    }

    private fun variant(gameId: Long,variantId: Long,locked: Boolean=false): GameVariant {
        require(gameId>0 && variantId>0)
        val variant=if(locked) em.find(GameVariant::class.java,variantId,LockModeType.PESSIMISTIC_WRITE)
        else em.find(GameVariant::class.java,variantId)
        requireNotNull(variant) { "Variant not found" }
        require(variant.game.id==gameId) { "Variant does not belong to this game" }
        return variant
    }
    private fun details(variant: GameVariant): VariantDetailsDto {
        val id=requireNotNull(variant.id); val gameId=requireNotNull(variant.game.id)
        val row=repository.findByIdOrNull(id)
        if(row!=null) { require(row.gameId==gameId); return row.toDto() }
        // Compatibility display defaults only: not a new detection, migration write or Steam override.
        val steam=variant.steamAppId?.takeIf { it.isNotBlank() }
        return VariantDetailsDto(gameId,id,if(steam==null) VariantStore.UNKNOWN else VariantStore.STEAM,
            steam,null,null,null,variant.steamAppIdManualOverride,"0",null,null,null)
    }
    private data class Snapshot(val current: VariantDetailsDto, val path: Path, val root: Path?)
    private fun snapshot(variant: GameVariant): Snapshot {
        val current=details(variant)
        val path=Path.of(variant.path).toAbsolutePath().normalize()
        // Scalar lookup avoids initializing Library's eager catalogue collections just to find roots.
        val configuredRoots=em.createQuery(
            "select d.internalPath from Game g join g.library l join l.directories d where g.id = :gameId",
            String::class.java
        ).setParameter("gameId",current.gameId).setMaxResults(65).resultList
        require(configuredRoots.size<=64) { "Too many configured roots for bounded local detection; enter metadata manually" }
        val roots=configuredRoots.map { Path.of(it).toAbsolutePath().normalize() }
            .filter { path.startsWith(it) }.sortedByDescending { it.nameCount }
        return Snapshot(current,path,roots.firstOrNull())
    }
    private fun previewOf(snapshot: Snapshot): VariantDetailsPreviewDto {
        val (current,path,root)=snapshot
        val detected=if(root==null) VariantLocalVersionPreviewDto(emptyList(),"Selected variant is not inside a configured library root; enter metadata manually",false)
        else detector.preview(root,path,
            steamAppId=if(current.store==VariantStore.STEAM) current.storeProductId else null,
            gogProductId=if(current.store==VariantStore.GOG) current.storeProductId else null,
            epicCatalogItemId=if(current.store==VariantStore.EPIC) current.storeProductId else null,
            allowUnknownFilenameHint=current.store==VariantStore.GOG)
        val digest=MessageDigest.getInstance("SHA-256")
        fun bind(value: Any?) { digest.update(if(value==null) 0.toByte() else 1.toByte())
            val bytes=(value?.toString()?:"").toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
        listOf(current.gameId,current.variantId,current.detailsRevision,current.store,current.storeProductId,current.edition,
            current.libraryVersion,current.buildId,current.detailsManualOverride,path,root,detected.message,detected.truncated).forEach(::bind)
        bind(detected.suggestions.size)
        detected.suggestions.forEach { listOf(it.kind,it.version,it.build,it.message,it.source).forEach(::bind) }
        return VariantDetailsPreviewDto(current,HexFormat.of().formatHex(digest.digest()),detected.suggestions,detected.message,detected.truncated)
    }
    private fun VariantDetails.toDto()=VariantDetailsDto(gameId,variantId,store,storeProductId,edition,libraryVersion,buildId,
        manualOverride,revision.toString(),source,evidence,observedAt)
    private fun revision(value: String): Long {
        require(value.matches(Regex("0|[1-9][0-9]{0,18}"))) { "Invalid details revision" }
        return requireNotNull(value.toLongOrNull()) { "Invalid details revision" }
    }
    private fun text(value: String?,limit: Int): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
        require(it.length<=limit && it.none { char -> char.isISOControl() }) { "Metadata value exceeds bounded text format" }
    }
    private fun validateProduct(store: VariantStore,product: String?) {
        if(product==null) return
        require(when(store) {
            VariantStore.UNKNOWN -> false
            VariantStore.STEAM -> product.matches(Regex("[1-9][0-9]{0,15}"))
            VariantStore.GOG -> product.matches(Regex("[1-9][0-9]{0,31}"))
            VariantStore.EPIC -> product.matches(Regex("[A-Za-z0-9_-]{1,128}"))
            VariantStore.OTHER -> true
        }) { "Product ID does not match the selected store" }
    }
    private fun administrator(): String {
        val auth=getCurrentAuth()
        if(auth==null || !auth.isAuthenticated || !auth.isAdmin()) throw AccessDeniedException("Administrator required")
        return auth.name.takeIf { it.isNotBlank() && it.length<=255 && it.none(Char::isISOControl) }
            ?: throw AccessDeniedException("Administrator identity required")
    }
}
