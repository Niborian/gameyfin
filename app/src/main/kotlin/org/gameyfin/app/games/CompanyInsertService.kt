package org.gameyfin.app.games

import org.gameyfin.app.games.entities.Company
import org.gameyfin.app.games.repositories.CompanyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Commits company creation before the caller attaches it to a game. */
@Service
class CompanyInsertService(private val companyRepository: CompanyRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insert(company: Company): Company = companyRepository.saveAndFlush(
        Company(name = company.name, type = company.type)
    )
}
