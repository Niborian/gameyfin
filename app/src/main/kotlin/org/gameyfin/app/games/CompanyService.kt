package org.gameyfin.app.games

import org.gameyfin.app.games.entities.Company
import org.gameyfin.app.games.repositories.CompanyRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val companyInsertService: CompanyInsertService
) {
    fun createOrGet(company: Company): Company {
        companyRepository.findByNameAndType(company.name, company.type)?.let { return it }

        try {
            // The insert must commit independently of the caller's game update. A save in
            // that transaction can fail only on flush/commit, after this method returns.
            companyInsertService.insert(company)
        } catch (e: DataIntegrityViolationException) {
            // Another game update committed the same (name, type) first.
            return companyRepository.findByNameAndType(company.name, company.type)
                ?: throw e
        }
        // Re-read in the caller's persistence context. New games cascade PERSIST to
        // their companies, so returning the insert transaction's detached entity fails.
        return companyRepository.findByNameAndType(company.name, company.type)
            ?: error("Committed company was not found")
    }
}
