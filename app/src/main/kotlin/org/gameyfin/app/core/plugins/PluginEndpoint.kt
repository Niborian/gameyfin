package org.gameyfin.app.core.plugins

import com.vaadin.flow.server.auth.AnonymousAllowed
import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.annotations.DynamicPublicAccess
import org.gameyfin.app.core.plugins.dto.PluginUpdateDto
import org.gameyfin.app.core.plugins.dto.PluginDto
import org.gameyfin.pluginapi.core.config.PluginConfigValidationResult
import reactor.core.publisher.Flux

@Endpoint
@DynamicPublicAccess
@AnonymousAllowed
class PluginEndpoint(
    private val pluginService: PluginService,
    private val access: PluginEndpointAccess,
) {

    fun subscribe(): Flux<List<PluginUpdateDto>> {
        val actor = access.capture()
        return access.stream(actor, { PluginService.subscribe() }) { update ->
            if (actor.administrator) update
            else PluginUpdateDto(id = update.id, state = update.state, priority = update.priority)
        }
    }

    fun getAll(): List<PluginDto> {
        val actor = access.capture()
        val result = pluginService.getAll(includeConfiguration = actor.administrator)
            .map { if (actor.administrator) it else it.copy(config = null, configMetadata = null, configValidation = null) }
            .sortedByDescending { it.priority }
        access.requireCurrent(actor)
        return result
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun enablePlugin(pluginId: String) {
        access.captureAdministrator()
        pluginService.enablePlugin(pluginId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun disablePlugin(pluginId: String) {
        access.captureAdministrator()
        pluginService.disablePlugin(pluginId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun setPluginPriorities(pluginPriorities: Map<String, Int>) {
        access.captureAdministrator()
        pluginService.setPluginPriorities(pluginPriorities)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun validatePluginConfig(pluginId: String): PluginConfigValidationResult {
        val actor = access.captureAdministrator()
        val result = pluginService.validatePluginConfig(pluginId, true)
        access.requireCurrent(actor)
        return result
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun validateNewConfig(pluginId: String, config: Map<String, String>): PluginConfigValidationResult {
        val actor = access.captureAdministrator()
        val result = pluginService.validatePluginConfig(pluginId, config)
        access.requireCurrent(actor)
        return result
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun updateConfig(pluginId: String, updatedConfig: Map<String, String>) {
        access.captureAdministrator()
        pluginService.updateConfig(pluginId, updatedConfig)
    }
}
