package org.gameyfin.app.users.avatar

import org.gameyfin.app.media.FileStorageService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

/** Explicit host-attested inventory only. Never discover trust from HTTP/DB data.
 * Startup creates a fresh challenge but does NOT load old grants from disk.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gameyfin.avatar", name = ["machine.enabled", "overlay.enabled", "operator.enabled"], havingValue = "true")
class AvatarOperatorBootstrap {
    @Bean(destroyMethod = "revokeOperatorAuthority")
    fun avatarOperatorRuntime(environment: Environment, dataSource: DataSource,
                              storage: FileStorageService): AvatarOperatorRuntime {
        val root = Path.of(environment.getRequiredProperty("gameyfin.avatar.operator.root"))
        val owner = root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
            environment.getRequiredProperty("gameyfin.avatar.operator.owner"))
        val identity = AvatarOperatorLoader.RuntimeIdentity.start(
            UUID.fromString(environment.getRequiredProperty("gameyfin.avatar.operator.installation")),
            environment.getRequiredProperty("gameyfin.avatar.operator.image-digest"),
            environment.getRequiredProperty("gameyfin.avatar.operator.build-checksum"))
        return AvatarOperatorRuntime(AvatarOperatorFiles(root, owner), identity, dataSource, storage)
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    internal fun avatarOperatorControl(environment: Environment, runtime: AvatarOperatorRuntime): AvatarOperatorControl {
        val root = Path.of(environment.getRequiredProperty("gameyfin.avatar.operator.root"))
        val owner = root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
            environment.getRequiredProperty("gameyfin.avatar.operator.owner"))
        return AvatarOperatorControl(root, owner, runtime::operatorStatus,
            runtime::revokeOperatorAuthority, runtime::reloadOperatorFiles, runtime::inspectNativeUser)
    }
}
