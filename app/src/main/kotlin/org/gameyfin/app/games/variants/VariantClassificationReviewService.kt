package org.gameyfin.app.games.variants

import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.games.dto.*
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.repositories.VariantClassificationReviewDecisionRepository
import org.gameyfin.app.games.repositories.VariantClassificationReviewRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Administrator review records only; this service deliberately has no scan, grouping, filesystem, or provider action. */
@Service
class VariantClassificationReviewService(
    private val gameRepository: GameRepository,
    private val reviewRepository: VariantClassificationReviewRepository,
    private val decisionRepository: VariantClassificationReviewDecisionRepository
) {
    @Transactional
    fun create(request: CreateVariantClassificationReviewRequestDto): VariantClassificationReviewDto {
        val sourceGame = gameRepository.findByIdOrNull(request.sourceGameId) ?: throw IllegalArgumentException("Source game not found")
        val targetGame = gameRepository.findByIdOrNull(request.targetGameId) ?: throw IllegalArgumentException("Target game not found")
        require(sourceGame.library.id == targetGame.library.id) { "Source and target must belong to the same library" }
        val sourceVariant = sourceGame.variants.firstOrNull { it.id == request.sourceVariantId }
            ?: throw IllegalArgumentException("Source variant does not belong to source game")
        require(request.confidence in 0..100) { "Confidence must be between 0 and 100" }
        val explanation = requiredText(request.explanation, "Explanation")
        val evidence = requiredText(request.evidence, "Evidence")
        return reviewRepository.save(VariantClassificationReview(
            sourceGame = sourceGame, sourceVariant = sourceVariant, targetGame = targetGame,
            confidence = request.confidence, explanation = explanation, evidence = evidence,
            createdBy = actor()
        )).toDto()
    }

    @Transactional
    fun updateState(reviewId: Long, request: UpdateVariantClassificationReviewStateRequestDto): VariantClassificationReviewDecisionDto {
        val review = reviewRepository.findByIdOrNull(reviewId) ?: throw IllegalArgumentException("Classification review not found")
        val previous = review.state
        review.state = request.state
        reviewRepository.save(review)
        return decisionRepository.save(VariantClassificationReviewDecision(
            review = review, previousState = previous, newState = request.state, actor = actor(),
            reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        )).toDto()
    }

    @Transactional(readOnly = true)
    fun history(reviewId: Long): List<VariantClassificationReviewDecisionDto> {
        reviewRepository.findByIdOrNull(reviewId) ?: throw IllegalArgumentException("Classification review not found")
        return decisionRepository.findAllByReviewIdOrderByDecidedAtAsc(reviewId).map { it.toDto() }
    }

    private fun requiredText(value: String, label: String): String = value.trim().also {
        require(it.isNotEmpty()) { "$label is required" }; require(it.length <= 4096) { "$label must not exceed 4096 characters" }
    }
    private fun actor() = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system"
    private fun VariantClassificationReview.toDto() = VariantClassificationReviewDto(requireNotNull(id), requireNotNull(sourceGame.id), requireNotNull(sourceVariant.id), requireNotNull(targetGame.id), confidence, explanation, evidence, state, createdAt, createdBy)
    private fun VariantClassificationReviewDecision.toDto() = VariantClassificationReviewDecisionDto(requireNotNull(id), previousState, newState, decidedAt, actor, reason)
}
