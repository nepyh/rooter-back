package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskUpdateRequest
import com.github.nepyh.rooter.module.planboard.dto.WeeklyPlanResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

class PlanTaskService {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** 본인 플랜보드의 특정 날짜 태스크 전체 (모든 보드 대상) */
    fun getDailyPlan(userId: Int, date: LocalDate): DailyPlanResponse = transaction {
        val user = UserRow.findById(userId)
            ?: return@transaction DailyPlanResponse(planDate = date.toString(), tasks = emptyList())

        val tasks = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.planDate eq date) and (PlanBoardTable.userId eq user.id) }
            .orderBy(PlanTaskTable.startTime)
            .map { PlanTaskRow.wrapRow(it) }
            .map { it.toResponse() }

        DailyPlanResponse(planDate = date.toString(), tasks = tasks)
    }

    /** 본인 플랜보드의 주간(월~일) 태스크 전체, referenceDate가 속한 주 기준 (모든 보드 대상) */
    fun getWeeklyPlan(userId: Int, referenceDate: LocalDate): WeeklyPlanResponse = transaction {
        val weekStart = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)

        val user = UserRow.findById(userId)
            ?: return@transaction WeeklyPlanResponse(
                weekStart = weekStart.toString(),
                weekEnd = weekEnd.toString(),
                days = (0..6).map { DailyPlanResponse(planDate = weekStart.plusDays(it.toLong()).toString(), tasks = emptyList()) }
            )

        val tasksByDate = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.planDate.between(weekStart, weekEnd)) and (PlanBoardTable.userId eq user.id) }
            .orderBy(PlanTaskTable.startTime)
            .map { row -> row[DailyPlanTable.planDate] to PlanTaskRow.wrapRow(row).toResponse() }
            .groupBy({ it.first }, { it.second })

        val days = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            DailyPlanResponse(planDate = date.toString(), tasks = tasksByDate[date].orEmpty())
        }

        WeeklyPlanResponse(weekStart = weekStart.toString(), weekEnd = weekEnd.toString(), days = days)
    }

    /** 특정 플랜보드의 날짜별 플랜 (소유권 확인) */
    fun getBoardDailyPlan(userId: Int, boardId: Int, targetDate: LocalDate): DailyPlanResponse = transaction {
        val board = PlanBoardRow.findById(boardId)
            ?: throw PlanBoardNotFoundException()

        if (board.user.id.value != userId) {
            throw PlanBoardForbiddenException()
        }

        val dailyPlan = DailyPlanRow.find {
            (DailyPlanTable.planBoardId eq board.id) and (DailyPlanTable.planDate eq targetDate)
        }.firstOrNull()

        if (dailyPlan == null) {
            return@transaction DailyPlanResponse(planDate = targetDate.toString(), tasks = emptyList())
        }

        val tasks = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlan.id }
            .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
            .map { it.toResponse() }

        DailyPlanResponse(dailyPlanId = dailyPlan.id.value, planDate = targetDate.toString(), tasks = tasks)
    }

    fun createTask(userId: Int, request: PlanTaskCreateRequest) = transaction {
        val board = PlanBoardRow.findById(request.planBoardId)
            ?: throw PlanBoardNotFoundException()

        if (board.user.id.value != userId) {
            throw PlanBoardForbiddenException()
        }

        if (request.taskName.isBlank() || request.taskName.length > 150) {
            throw PlanTaskValidationException.InvalidTaskNameException()
        }

        val date = runCatching { LocalDate.parse(request.planDate) }
            .getOrElse { throw PlanTaskValidationException.InvalidPlanDateException() }

        val startTime = runCatching { LocalTime.parse(request.startTime) }
            .getOrElse { throw PlanTaskValidationException.InvalidTimeFormatException() }
        val endTime = runCatching { LocalTime.parse(request.endTime) }
            .getOrElse { throw PlanTaskValidationException.InvalidTimeFormatException() }

        if (!endTime.isAfter(startTime)) {
            throw PlanTaskValidationException.InvalidTimeRangeException()
        }

        if (request.estimatedMinutes < 1) {
            throw PlanTaskValidationException.InvalidEstimatedMinutesException()
        }

        if (date.isBefore(board.startDate) || date.isAfter(board.endDate)) {
            throw PlanTaskValidationException.PlanDateOutOfRangeException()
        }

        // 동시 요청에도 daily_plan 이 중복 생성되지 않도록 insertIgnore (DDL 유니크 제약과 짝)
        DailyPlanTable.insertIgnore {
            it[planBoardId] = board.id
            it[planDate] = date
        }

        val dailyPlan = DailyPlanRow.find {
            (DailyPlanTable.planBoardId eq board.id) and (DailyPlanTable.planDate eq date)
        }.first()

        PlanTaskRow.new {
            this.dailyPlan = dailyPlan
            taskName = request.taskName
            this.startTime = startTime
            this.endTime = endTime
            estimatedMinutes = request.estimatedMinutes
        }
    }

    /**
     * 태스크 완료 처리/취소 (본인 플랜보드 소유권 확인). 예전 앱 호환용 — 상태 변경은 [updateTask] 의 status 로 한다.
     * isCompleted=true 는 DONE, false 는 PENDING 으로 바꾸는 것과 같고 규칙은 [changeStatus] 를 따른다.
     * 지금 상태와 같은 값을 보내는 건 막지 않는다 (실패한 할일에 false 를 보내도 그대로 성공).
     */
    fun completeTask(userId: Int, taskId: Int, isCompleted: Boolean): PlanTaskResponse = transaction {
        val task = PlanTaskRow.findById(taskId)
            ?: throw PlanTaskNotFoundException()

        if (task.dailyPlan.planBoard.user.id.value != userId) {
            throw PlanTaskNotFoundException()
        }

        if ((task.status == PlanTaskStatus.DONE) != isCompleted) {
            task.changeStatus(if (isCompleted) PlanTaskStatus.DONE else PlanTaskStatus.PENDING)
        }
        task.toResponse()
    }

    /**
     * 사용자가 바꿀 수 있는 상태 전환만 허용한다. 지금과 같은 상태면 아무것도 하지 않는다.
     * - PENDING → FAILED: 실패 처리
     * - DONE → PENDING: 완료 취소. 태스크 퀴즈를 통과해 완료된 할일은 TASK_FINALIZED
     * - → DONE: 태스크 퀴즈 통과(TaskQuizService.submitQuiz)로만 되므로 TASK_QUIZ_REQUIRED (#259)
     * - FAILED → 그 외, DONE → FAILED: TASK_FINALIZED
     */
    private fun PlanTaskRow.changeStatus(target: PlanTaskStatus) {
        if (status == target) return
        when {
            status == PlanTaskStatus.FAILED -> throw PlanTaskValidationException.TaskFinalizedException()
            target == PlanTaskStatus.DONE -> throw PlanTaskValidationException.TaskQuizRequiredException()
            status == PlanTaskStatus.DONE && (target == PlanTaskStatus.FAILED || hasPassedQuiz(id.value)) ->
                throw PlanTaskValidationException.TaskFinalizedException()
        }
        status = target
    }

    /** 태스크 퀴즈를 통과한 태스크인지 */
    private fun hasPassedQuiz(taskId: Int): Boolean =
        !TaskQuizAttemptTable.selectAll().where {
            (TaskQuizAttemptTable.planTaskId eq taskId) and (TaskQuizAttemptTable.passed eq true)
        }.empty()

    /** taskName/startTime/endTime/estimatedMinutes/status 중 전달된 필드만 수정 (본인 플랜보드 소유권 확인) */
    fun updateTask(userId: Int, taskId: Int, request: PlanTaskUpdateRequest): PlanTaskResponse = transaction {
        val task = PlanTaskRow.findById(taskId)
            ?: throw PlanTaskNotFoundException()

        if (task.dailyPlan.planBoard.user.id.value != userId) {
            throw PlanTaskNotFoundException()
        }

        request.taskName?.let {
            if (it.isBlank() || it.length > 150) throw PlanTaskValidationException.InvalidTaskNameException()
            task.taskName = it
        }

        val newStartTime = request.startTime?.let {
            runCatching { LocalTime.parse(it) }.getOrElse { throw PlanTaskValidationException.InvalidTimeFormatException() }
        } ?: task.startTime
        val newEndTime = request.endTime?.let {
            runCatching { LocalTime.parse(it) }.getOrElse { throw PlanTaskValidationException.InvalidTimeFormatException() }
        } ?: task.endTime
        if (!newEndTime.isAfter(newStartTime)) {
            throw PlanTaskValidationException.InvalidTimeRangeException()
        }
        task.startTime = newStartTime
        task.endTime = newEndTime

        request.estimatedMinutes?.let {
            if (it < 1) throw PlanTaskValidationException.InvalidEstimatedMinutesException()
            task.estimatedMinutes = it
        }

        request.status?.let { task.changeStatus(it) }

        task.toResponse()
    }

    /** 태스크 삭제 (본인 플랜보드 소유권 확인) */
    fun deleteTask(userId: Int, taskId: Int) = transaction {
        val task = PlanTaskRow.findById(taskId)
            ?: throw PlanTaskNotFoundException()

        if (task.dailyPlan.planBoard.user.id.value != userId) {
            throw PlanTaskNotFoundException()
        }

        task.delete()
    }

    private fun PlanTaskRow.toResponse() = PlanTaskResponse(
        id = id.value,
        dailyPlanId = readValues[PlanTaskTable.dailyPlanId].value,
        taskName = taskName,
        startTime = startTime.format(timeFormat),
        endTime = endTime.format(timeFormat),
        estimatedMinutes = estimatedMinutes,
        isCompleted = status == PlanTaskStatus.DONE,
        status = status
    )
}
