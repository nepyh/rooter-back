package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.module.feedback.dto.FeedbackResponse
import com.github.nepyh.rooter.module.feedback.dto.FeedbackSubmitRequest
import com.github.nepyh.rooter.module.feedback.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackAlreadySubmittedException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackValidationException
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbacks
import com.github.nepyh.rooter.module.planboard.model.DailyPlans
import com.github.nepyh.rooter.module.planboard.model.PlanBoards
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

private val VALID_DIFFICULTIES = setOf("쉬움", "적당", "어려움")

class FeedbackService {

    private suspend fun requireOwnedDailyPlan(userId: Int, dailyPlanId: Int) =
        (DailyPlans innerJoin PlanBoards)
            .selectAll()
            .where { (DailyPlans.id eq dailyPlanId) and (PlanBoards.userId eq userId) }
            .firstOrNull()
            ?: throw DailyPlanNotFoundException()

    suspend fun submitFeedback(userId: Int, dailyPlanId: Int, request: FeedbackSubmitRequest): FeedbackResponse =
        newSuspendedTransaction {
            requireOwnedDailyPlan(userId, dailyPlanId)

            if (request.difficulty !in VALID_DIFFICULTIES) {
                throw FeedbackValidationException.InvalidDifficultyException()
            }
            if (request.timeSpentMinutes != null && request.timeSpentMinutes < 1) {
                throw FeedbackValidationException.InvalidTimeSpentMinutesException()
            }
            if (request.focusLevel != null && request.focusLevel !in 1..5) {
                throw FeedbackValidationException.InvalidFocusLevelException()
            }

            val alreadySubmitted = DailyFeedbacks.selectAll()
                .where { DailyFeedbacks.dailyPlanId eq dailyPlanId }
                .firstOrNull() != null
            if (alreadySubmitted) {
                throw FeedbackAlreadySubmittedException()
            }

            val row = DailyFeedbacks.insert {
                it[this.dailyPlanId] = dailyPlanId
                it[difficulty] = request.difficulty
                it[timeSpentMinutes] = request.timeSpentMinutes
                it[focusLevel] = request.focusLevel
            }.resultedValues!!.first()

            row.toFeedbackResponse()
        }

    suspend fun getFeedback(userId: Int, dailyPlanId: Int): FeedbackResponse = newSuspendedTransaction {
        requireOwnedDailyPlan(userId, dailyPlanId)

        DailyFeedbacks.selectAll()
            .where { DailyFeedbacks.dailyPlanId eq dailyPlanId }
            .firstOrNull()
            ?.toFeedbackResponse()
            ?: throw FeedbackNotFoundException()
    }

    private fun ResultRow.toFeedbackResponse() = FeedbackResponse(
        id = this[DailyFeedbacks.id],
        dailyPlanId = this[DailyFeedbacks.dailyPlanId],
        difficulty = this[DailyFeedbacks.difficulty],
        timeSpentMinutes = this[DailyFeedbacks.timeSpentMinutes],
        focusLevel = this[DailyFeedbacks.focusLevel],
        createdAt = this[DailyFeedbacks.createdAt].toString()
    )
}
