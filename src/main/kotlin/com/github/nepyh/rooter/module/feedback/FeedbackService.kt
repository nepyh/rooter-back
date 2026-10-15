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
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskNotFoundException
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime

private val VALID_DIFFICULTIES = setOf("쉬움", "적당", "어려움")

class FeedbackService(
    private val replanLlmClient: ReplanLlmClient
) {

    /** 소유자 확인과 함께 daily_plans/plan_boards 양쪽 컬럼을 한 번에 읽어야 해서 Table DSL 을 유지한다. */
    private fun requireOwnedDailyPlan(userId: Int, dailyPlanId: Int): ResultRow =
        (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw DailyPlanNotFoundException()

    /**
     * 할일 소유권 확인 — 할일 → daily_plan → plan_board.user_id.
     * 없는 할일과 남의 할일을 구분하지 않고 같은 404(PLAN_TASK_NOT_FOUND) 로 처리한다.
     */
    private fun requireOwnedTask(userId: Int, taskId: Int): OwnedTask {
        val task = PlanTaskRow.findById(taskId) ?: throw PlanTaskNotFoundException()
        val dailyPlanId = task.dailyPlan.id.value

        val owned = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw PlanTaskNotFoundException()

        return OwnedTask(taskId = taskId, dailyPlanId = owned[DailyPlanTable.id].value)
    }

    /**
     * 할일 하나에 대한 설문 제출. 할일당 1건만 허용한다.
     * 재조정(AI)은 여기서 돌지 않는다 — 자정 배치 [FeedbackReplanJob] 이 하루 1회 모아서 실행한다.
     */
    suspend fun submitFeedback(userId: Int, taskId: Int, request: FeedbackSubmitRequest): FeedbackResponse =
        newSuspendedTransaction {
            val task = requireOwnedTask(userId, taskId)

            if (request.difficulty !in VALID_DIFFICULTIES) {
                throw FeedbackValidationException.InvalidDifficultyException()
            }
            if (request.timeSpentMinutes != null && request.timeSpentMinutes < 1) {
                throw FeedbackValidationException.InvalidTimeSpentMinutesException()
            }
            if (request.focusLevel != null && request.focusLevel !in 1..5) {
                throw FeedbackValidationException.InvalidFocusLevelException()
            }

            // DDL 의 uq_daily_feedback_plan_task 와 같은 규칙을 서비스에서 먼저 확인해 409 로 돌려준다.
            val alreadySubmitted = DailyFeedbackRow.find { DailyFeedbackTable.planTaskId eq taskId }
                .firstOrNull() != null
            if (alreadySubmitted) {
                throw FeedbackAlreadySubmittedException()
            }

            DailyFeedbackRow.new {
                this.dailyPlan = DailyPlanRow[task.dailyPlanId]
                this.planTaskId = EntityID(taskId, PlanTaskTable)
                difficulty = request.difficulty
                timeSpentMinutes = request.timeSpentMinutes
                focusLevel = request.focusLevel
            }.toFeedbackResponse(task.dailyPlanId)
        }

    /** 할일 하나에 제출된 설문 단건. 없으면 404. */
    suspend fun getFeedbackByTask(userId: Int, taskId: Int): FeedbackResponse = newSuspendedTransaction {
        val task = requireOwnedTask(userId, taskId)

        DailyFeedbackRow.find { DailyFeedbackTable.planTaskId eq taskId }
            .firstOrNull()
            ?.toFeedbackResponse(task.dailyPlanId)
            ?: throw FeedbackNotFoundException()
    }

    /** 그날 daily_plan 에 제출된 설문 목록 (할일 시작 시간 순, 할일을 모르는 옛 행은 뒤로). */
    suspend fun getFeedbacksByDailyPlan(userId: Int, dailyPlanId: Int): List<FeedbackResponse> =
        newSuspendedTransaction {
            requireOwnedDailyPlan(userId, dailyPlanId)

            // 정렬 기준(할일 시작 시간)만 필요해서 설문마다 참조 엔티티를 로드하지 않고 시작 시간을 한 번에 읽는다.
            val startTimes = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
                .associate { it.id.value to it.startTime }

            DailyFeedbackRow.find { DailyFeedbackTable.dailyPlanId eq dailyPlanId }
                .map { it.toFeedbackResponse(dailyPlanId) }
                .sortedBy { feedback -> feedback.taskId?.let { startTimes[it] } ?: LocalTime.MAX }
        }

    /**
     * 자정 배치([FeedbackReplanJob])가 그 보드·그 날짜에 대해 하루 1회 호출한다.
     * 그날 제출된 설문과 그날 오답 문제를 근거로 AI 제안을 받아, 미래 날짜(planDate + dayOffset >= 1,
     * 보드 end_date 를 넘지 않게)에 보충/심화 태스크를 추가한다.
     */
    suspend fun replanForDay(
        userId: Int,
        planBoardId: Int,
        planDate: LocalDate
    ): List<ReplanAdjustmentResponse> {
        val context = buildReplanContext(userId, planBoardId, planDate) ?: return emptyList()

        // AI 호출은 트랜잭션 밖에서 (LLM 지연 동안 DB 커넥션을 붙잡지 않는다)
        val suggestions = replanLlmClient.suggestAdjustments(context)
        if (suggestions.isEmpty()) return emptyList()

        return newSuspendedTransaction { addAdjustmentTasks(planBoardId, planDate, suggestions) }
    }

    /**
     * 조정 근거 문자열. AI 호출을 생략해야 하면 null 을 돌려준다.
     * - 제출된 설문이 0건이면 조정 트리거 자체가 없다 (호출 대상에서 빠짐)
     * - 오답이 0건이고 제출된 모든 설문이 적당(집중도는 응답 안 함 또는 3 이상)이면 호출하지 않는다
     * 설문 일부만 제출됐으면 그 일부만 근거로 쓴다.
     */
    internal suspend fun buildReplanContext(
        userId: Int,
        planBoardId: Int,
        planDate: LocalDate
    ): String? = newSuspendedTransaction {
        val dailyPlanIds = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.planBoardId eq planBoardId) and (DailyPlanTable.planDate eq planDate) }
            .map { it[DailyPlanTable.id].value }

        if (dailyPlanIds.isEmpty()) return@newSuspendedTransaction null

        // 설문이 달린 할일과 조인해 "어느 할일의 설문인지"까지 근거에 넣는다 (제출 안 한 할일은 빠짐).
        val surveys = (DailyFeedbackTable innerJoin PlanTaskTable)
            .selectAll()
            .where { DailyFeedbackTable.dailyPlanId inList dailyPlanIds }
            .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
            .map {
                SurveyContext(
                    taskName = it[PlanTaskTable.taskName],
                    difficulty = it[DailyFeedbackTable.difficulty],
                    timeSpentMinutes = it[DailyFeedbackTable.timeSpentMinutes],
                    focusLevel = it[DailyFeedbackTable.focusLevel]
                )
            }
        if (surveys.isEmpty()) return@newSuspendedTransaction null

        // 퀴즈 오답을 모으려면 3개 테이블 조인이 필요해 Table DSL 을 유지한다.
        val wrongQuestionTexts = (DailyQuizQuestionTable innerJoin DailyQuizChoiceTable innerJoin DailyQuizAttemptTable)
            .selectAll()
            .where {
                (DailyQuizQuestionTable.dailyPlanId inList dailyPlanIds) and
                    (DailyQuizAttemptTable.userId eq userId) and
                    (DailyQuizChoiceTable.isCorrect eq false)
            }
            .map { it[DailyQuizQuestionTable.questionText] }
            .distinct()

        val allSurveysOkay = surveys.all {
            it.difficulty == "적당" && (it.focusLevel == null || it.focusLevel >= 3)
        }
        if (wrongQuestionTexts.isEmpty() && allSurveysOkay) return@newSuspendedTransaction null

        buildString {
            appendLine("제출된 설문 ${surveys.size}건 (할일별)")
            surveys.forEach { survey ->
                appendLine(
                    "- 할일: ${survey.taskName} / 체감 난이도: ${survey.difficulty} / " +
                        "예상 소요 시간 대비 실제: ${survey.timeSpentMinutes?.let { "${it}분" } ?: "응답 안 함"} / " +
                        "집중도(1~5): ${survey.focusLevel ?: "응답 안 함"}"
                )
            }
            appendLine("오늘 틀린 문제: ${wrongQuestionTexts.joinToString("; ").ifBlank { "없음(퀴즈 미응시 포함)" }}")
        }
    }

    /** AI 제안을 미래 날짜의 보충 태스크로 붙인다. */
    private fun addAdjustmentTasks(
        planBoardId: Int,
        planDate: LocalDate,
        suggestions: List<ReplanSuggestion>
    ): List<ReplanAdjustmentResponse> {
        val boardEndDate = PlanBoardRow[planBoardId].endDate

        return suggestions.mapNotNull { suggestion ->
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

    private fun DailyFeedbackRow.toFeedbackResponse(dailyPlanId: Int) = FeedbackResponse(
        id = this.id.value,
        dailyPlanId = dailyPlanId,
        taskId = this.planTaskId?.value,
        difficulty = this.difficulty,
        timeSpentMinutes = this.timeSpentMinutes,
        focusLevel = this.focusLevel,
        createdAt = this.createdAt.toString()
    )
}

/** 소유권을 확인한 할일과 그 할일이 속한 일일 계획. */
private data class OwnedTask(
    val taskId: Int,
    val dailyPlanId: Int
)

/** 조정 근거에 넣을 제출된 설문 하나. */
private data class SurveyContext(
    val taskName: String,
    val difficulty: String,
    val timeSpentMinutes: Int?,
    val focusLevel: Int?
)
