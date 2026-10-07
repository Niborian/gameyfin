package org.gameyfin.app.games.repositories

import org.gameyfin.app.games.entities.GameVariant
import org.springframework.data.jpa.repository.JpaRepository

interface GameVariantRepository : JpaRepository<GameVariant, Long>
