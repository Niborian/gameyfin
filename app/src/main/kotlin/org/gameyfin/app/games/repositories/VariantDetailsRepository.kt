package org.gameyfin.app.games.repositories

import org.gameyfin.app.games.entities.VariantDetails
import org.springframework.data.jpa.repository.JpaRepository

interface VariantDetailsRepository : JpaRepository<VariantDetails, Long>
