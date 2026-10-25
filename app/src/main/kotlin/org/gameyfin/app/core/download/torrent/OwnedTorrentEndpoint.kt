package org.gameyfin.app.core.download.torrent

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Hilla POST/CSRF transport. No public-mode annotation and no arbitrary path input. */
@Endpoint
@PermitAll
class OwnedTorrentEndpoint internal constructor(private val facade: OwnedTorrentFacade, private val settings: OwnedTorrentSettings) {
    fun create(selection: OwnedTorrentSelection): OwnedTorrentStatus = facade.create(selection)
    fun status(id: String): OwnedTorrentStatus = facade.status(UUID.fromString(id))
    @RolesAllowed(Role.Names.ADMIN)
    fun getSettings(): OwnedTorrentSettingsView = settings.view()
    @RolesAllowed(Role.Names.ADMIN)
    fun updateSettings(update: OwnedTorrentSettingsUpdate): OwnedTorrentSettingsView = settings.update(update)
    @RolesAllowed(Role.Names.ADMIN)
    fun retire(id: String, retryUncertainRemoval: Boolean = false): OwnedTorrentStatus = facade.retire(UUID.fromString(id), retryUncertainRemoval)
    @RolesAllowed(Role.Names.ADMIN)
    fun inspect(id: String): OwnedTorrentStatus = facade.inspect(UUID.fromString(id))
    @RolesAllowed(Role.Names.ADMIN)
    fun retained(): List<OwnedTorrentStatus> = facade.retained()
    @RolesAllowed(Role.Names.ADMIN)
    fun recover(id: String, action: OwnedTorrentRecoveryAction): OwnedTorrentStatus = facade.recover(UUID.fromString(id), action)
    fun available(): Boolean = facade.available()
}

@RestController
class OwnedTorrentMetadataEndpoint internal constructor(private val facade: OwnedTorrentFacade) {
    /** Read-only retrieval of already prepared metadata. No acquire/create/start on GET. */
    @GetMapping("/owned-torrent/{id}/metadata")
    fun metadata(@PathVariable id: UUID): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Content-Type", "application/x-bittorrent")
        .header("Content-Disposition", "attachment; filename=\"owned-selection.torrent\"")
        .body(facade.metadata(id))
}
