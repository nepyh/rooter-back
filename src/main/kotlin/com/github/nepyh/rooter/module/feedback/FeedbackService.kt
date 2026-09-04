package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.module.feedback.dto.FeedbackResponse
import com.github.nepyh.rooter.module.feedback.dto.FeedbackSubmitRequest
import com.github.nepyh.rooter.module.feedback.dto.ReplanAdjustmentResponse
import com.github.nepyh.rooter.module.feedback.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackAlreadySubmittedException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackValidationException
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbacks
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttempts
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoices
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestions
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime

private val VALID_DIFFICULTIES = setOf("쉬움", "적당", "어려움")

class FeedbackService(
    private val replanLlmClient: ReplanLlmClient
) {

    private suspend fun requireOwnedDailyPlan(userId: Int, dailyPlanId: Int) =
        (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw DailyPlanNotFoundException()

    suspend fun submitFeedback(userId: Int, dailyPlanId: Int, request: FeedbackSubmitRequest): FeedbackResponse {
        val (feedbackRow, planBoardId, planDate, boardEndDate) = newSuspendedTransaction {
            val dailyPlanRow = requireOwnedDailyPlan(userId, dailyPlanId)

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

            val planBoardId = dailyPlanRow[PlanBoardTable.id].value
            val planDate = dailyPlanRow[DailyPlanTable.planDate]
            val boardEndDate = dailyPlanRow[PlanBoardTable.endDate]

            Quadruple(row, planBoardId, planDate, boardEndDate)
        }

        val adjustments = runCatching {
            replan(userId, dailyPlanId, planBoardId, planDate, boardEndDate, request)
        }.getOrElse { emptyList() }

        return feedbackRow.toFeedbackResponse(adjustments)
    }

    suspend fun getFeedback(userId: Int, dailyPlanId: Int): FeedbackResponse = newSuspendedTransaction {
        requireOwnedDailyPlan(userId, dailyPlanId)

        DailyFeedbacks.selectAll()
            .where { DailyFeedbacks.dailyPlanId eq dailyPlanId }
            .firstOrNull()
            ?.toFeedbackResponse(emptyList())
            ?: throw FeedbackNotFoundException()
    }

    /**
     * 퀴즈 오답 + 방금 제출된 설문(난이도/소요시간/집중도)을 근거로, AI가 남은 날짜에 보충/심화
     * 태스크를 추가 제안한다. AI 호출이 실패해도 피드백 제출 자체는 이미 끝난 뒤이므로 여기서 던진
     * 예외는 submitFeedback 쪽에서 흡수하고 빈 리스트로 대체한다 (replan은 부가 기능, 필수 아님).
     */
    private suspend fun replan(
        userId: Int,
        dailyPlanId: Int,
        planBoardId: Int,
        planDate: LocalDate,
        boardEndDate: LocalDate,
        feedback: FeedbackSubmitRequest
    ): List<ReplanAdjustmentResponse> = newSuspendedTransaction {
        val wrongQuestionTexts = (DailyQuizQuestions innerJoin DailyQuizChoices innerJoin DailyQuizAttempts)
            .selectAll()
            .where {
                (DailyQuizQuestions.dailyPlanId eq dailyPlanId) and
                    (DailyQuizAttempts.userId eq userId) and
                    (DailyQuizChoices.isCorrect eq false)
            }
            .map { it[DailyQuizQuestions.questionText] }
            .distinct()

        if (wrongQuestionTexts.isEmpty() && feedback.difficulty == "적당" && (feedback.focusLevel == null || feedback.focusLevel >= 3)) {
            // 오답도 없고 특별히 힘들었다는 신호도 없으면 굳이 AI를 호출하지 않음
            return@newSuspendedTransaction emptyList()
        }

        val context = buildString {
            appendLine("체감 난이도: ${feedback.difficulty}")
            appendLine("예상 소요 시간 대비 실제: ${feedback.timeSpentMinutes?.let { "${it}분" } ?: "응답 안 함"}")
            appendLine("집중도(1~5): ${feedback.focusLevel ?: "응답 안 함"}")
            appendLine("오늘 틀린 문제: ${wrongQuestionTexts.joinToString("; ").ifBlank { "없음(퀴즈 미응시 포함)" }}")
        }

        val suggestions = replanLlmClient.suggestAdjustments(context)

        suggestions.mapNotNull { suggestion ->
            if (suggestion.dayOffset < 1) return@mapNotNull null
            val targetDate = planDate.plusDays(suggestion.dayOffset.toLong())
            if (targetDate.isAfter(boardEndDate)) return@mapNotNull null

            val targetDailyPlanId = findOrCreateDailyPlan(planBoardId, targetDate)
            val startTime = lastTaskEndTime(targetDailyPlanId) ?: LocalTime.of(9, 0)
            val taskName = suggestion.taskName.take(150)
            val minutes = suggestion.estimatedMinutes.coerceIn(5, 120)

            PlanTaskTable.insert {
                it[this.dailyPlanId] = targetDailyPlanId
                it[this.taskName] = taskName
                it[this.startTime] = startTime
                it[endTime] = startTime.plusMinutes(minutes.toLong())
                it[estimatedMinutes] = minutes
            }

            ReplanAdjustmentResponse(
                dailyPlanId = targetDailyPlanId,
                planDate = targetDate.toString(),
                taskName = taskName
            )
        }
    }

    private fun findOrCreateDailyPlan(planBoardId: Int, date: LocalDate): Int {
        val existing = DailyPlanTable.selectAll()
            .where { (DailyPlanTable.planBoardId eq planBoardId) and (DailyPlanTable.planDate eq date) }
            .firstOrNull()

        if (existing != null) return existing[DailyPlanTable.id].value

        return (DailyPlanTable.insert {
            it[this.planBoardId] = planBoardId
            it[planDate] = date
        } get DailyPlanTable.id).value
    }

    private fun lastTaskEndTime(dailyPlanId: Int): LocalTime? =
        PlanTaskTable.selectAll()
            .where { PlanTaskTable.dailyPlanId eq dailyPlanId }
            .orderBy(PlanTaskTable.endTime to SortOrder.DESC)
            .firstOrNull()
            ?.get(PlanTaskTable.endTime)

    private fun ResultRow.toFeedbackResponse(adjustments: List<ReplanAdjustmentResponse>) = FeedbackResponse(
        id = this[DailyFeedbacks.id],
        dailyPlanId = this[DailyFeedbacks.dailyPlanId],
        difficulty = this[DailyFeedbacks.difficulty],
        timeSpentMinutes = this[DailyFeedbacks.timeSpentMinutes],
        focusLevel = this[DailyFeedbacks.focusLevel],
        createdAt = this[DailyFeedbacks.createdAt].toString(),
        insertedAdjustmentTasks = adjustments
    )
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
