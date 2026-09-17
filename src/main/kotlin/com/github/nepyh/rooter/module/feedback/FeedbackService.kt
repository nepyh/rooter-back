package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.module.feedback.dto.FeedbackResponse
import com.github.nepyh.rooter.module.feedback.dto.FeedbackSubmitRequest
import com.github.nepyh.rooter.module.feedback.dto.ReplanAdjustmentResponse
import com.github.nepyh.rooter.module.feedback.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackAlreadySubmittedException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackValidationException
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackRow
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime

private val VALID_DIFFICULTIES = setOf("쉬움", "적당", "어려움")

class FeedbackService(
    private val replanLlmClient: ReplanLlmClient
) {

    /** 소유자 확인과 함께 daily_plans/plan_boards 양쪽 컬럼을 한 번에 읽어야 해서 Table DSL 을 유지한다. */
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

            val alreadySubmitted = DailyFeedbackRow.find { DailyFeedbackTable.dailyPlanId eq dailyPlanId }
                .firstOrNull() != null
            if (alreadySubmitted) {
                throw FeedbackAlreadySubmittedException()
            }

            val row = DailyFeedbackRow.new {
                this.dailyPlan = DailyPlanRow[dailyPlanId]
                difficulty = request.difficulty
                timeSpentMinutes = request.timeSpentMinutes
                focusLevel = request.focusLevel
            }

            val planBoardId = dailyPlanRow[PlanBoardTable.id].value
            val planDate = dailyPlanRow[DailyPlanTable.planDate]
            val boardEndDate = dailyPlanRow[PlanBoardTable.endDate]

            Quadruple(row, planBoardId, planDate, boardEndDate)
        }

        val adjustments = runCatching {
            replan(userId, dailyPlanId, planBoardId, planDate, boardEndDate, request)
        }.getOrElse { emptyList() }

        return feedbackRow.toFeedbackResponse(dailyPlanId, adjustments)
    }

    suspend fun getFeedback(userId: Int, dailyPlanId: Int): FeedbackResponse = newSuspendedTransaction {
        requireOwnedDailyPlan(userId, dailyPlanId)

        DailyFeedbackRow.find { DailyFeedbackTable.dailyPlanId eq dailyPlanId }
            .firstOrNull()
            ?.toFeedbackResponse(dailyPlanId, emptyList())
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
        // 퀴즈 오답을 모으려면 3개 테이블 조인이 필요해 Table DSL 을 유지한다.
        val wrongQuestionTexts = (DailyQuizQuestionTable innerJoin DailyQuizChoiceTable innerJoin DailyQuizAttemptTable)
            .selectAll()
            .where {
                (DailyQuizQuestionTable.dailyPlanId eq dailyPlanId) and
                    (DailyQuizAttemptTable.userId eq userId) and
                    (DailyQuizChoiceTable.isCorrect eq false)
            }
            .map { it[DailyQuizQuestionTable.questionText] }
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

            val targetDailyPlan = findOrCreateDailyPlan(planBoardId, targetDate)
            val startTime = lastTaskEndTime(targetDailyPlan.id.value) ?: LocalTime.of(9, 0)
            val taskName = suggestion.taskName.take(150)
            val minutes = suggestion.estimatedMinutes.coerceIn(5, 120)

            PlanTaskRow.new {
                dailyPlan = targetDailyPlan
                this.taskName = taskName
                this.startTime = startTime
                endTime = startTime.plusMinutes(minutes.toLong())
                estimatedMinutes = minutes
            }

            ReplanAdjustmentResponse(
                dailyPlanId = targetDailyPlan.id.value,
                planDate = targetDate.toString(),
                taskName = taskName
            )
        }
    }

    private fun findOrCreateDailyPlan(planBoardId: Int, date: LocalDate): DailyPlanRow {
        val existing = DailyPlanRow.find {
            (DailyPlanTable.planBoardId eq planBoardId) and (DailyPlanTable.planDate eq date)
        }.firstOrNull()

        if (existing != null) return existing

        return DailyPlanRow.new {
            planBoard = PlanBoardRow[planBoardId]
            planDate = date
        }
    }

    private fun lastTaskEndTime(dailyPlanId: Int): LocalTime? =
        PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
            .orderBy(PlanTaskTable.endTime to SortOrder.DESC)
            .firstOrNull()
            ?.endTime

    private fun DailyFeedbackRow.toFeedbackResponse(
        dailyPlanId: Int,
        adjustments: List<ReplanAdjustmentResponse>
    ) = FeedbackResponse(
        id = this.id.value,
        dailyPlanId = dailyPlanId,
        difficulty = this.difficulty,
        timeSpentMinutes = this.timeSpentMinutes,
        focusLevel = this.focusLevel,
        createdAt = this.createdAt.toString(),
        insertedAdjustmentTasks = adjustments
    )
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
