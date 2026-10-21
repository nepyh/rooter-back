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
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.taskquiz.MAX_ATTEMPTS
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.DayOfWeek
import java.time.Duration
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

        val taskRows = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.planDate eq date) and (PlanBoardTable.userId eq user.id) }
            .orderBy(PlanTaskTable.startTime)
            .map { BoardTask(it[DailyPlanTable.planBoardId].value, isManualPlanBoard(it[PlanBoardTable.examDate]), PlanTaskRow.wrapRow(it)) }
        val subjectNames = subjectNamesOf(taskRows.map { it.task })

        DailyPlanResponse(
            planDate = date.toString(),
            tasks = taskRows.map { (planBoardId, isManual, task) -> task.toResponse(planBoardId, isManual, task.subjectNameFrom(subjectNames)) }
        )
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

        val taskRows = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.planDate.between(weekStart, weekEnd)) and (PlanBoardTable.userId eq user.id) }
            .orderBy(PlanTaskTable.startTime)
            .map { row -> row[DailyPlanTable.planDate] to BoardTask(row[DailyPlanTable.planBoardId].value, isManualPlanBoard(row[PlanBoardTable.examDate]), PlanTaskRow.wrapRow(row)) }
        val subjectNames = subjectNamesOf(taskRows.map { it.second.task })

        val tasksByDate = taskRows.groupBy({ it.first }, { (_, boardTask) ->
            val (planBoardId, isManual, taskRow) = boardTask
            taskRow.toResponse(planBoardId, isManual, taskRow.subjectNameFrom(subjectNames))
        })

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

        val taskRows = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlan.id }
            .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
            .toList()
        val subjectNames = subjectNamesOf(taskRows)

        DailyPlanResponse(
            dailyPlanId = dailyPlan.id.value,
            planDate = targetDate.toString(),
            tasks = taskRows.map { it.toResponse(board.id.value, isManualPlanBoard(board.examDate), it.subjectNameFrom(subjectNames)) }
        )
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

        // 프론트가 고른 교과서가 오면 그 교과서의 과목을 할일에 붙인다 (안 보내면 과목 미지정)
        val textbookSubjectId = request.textbookId?.let { textbookId ->
            val textbook = TextbookRow.findById(textbookId)
                ?: throw PlanTaskValidationException.InvalidTextbookException()
            textbook.subject.id.value
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
            this.subjectId = textbookSubjectId?.let { EntityID(it, SubjectTable) }
        }
    }

    /**
     * 태스크 완료 처리/취소 (본인 플랜보드 소유권 확인).
     * 태스크 퀴즈로 결과가 확정된 태스크(통과, 또는 마지막 차수까지 불합격)는 완료 상태를 바꿀 수 없다.
     * 미완료 → 완료는 태스크 퀴즈 통과로만 일어나므로 여기서는 막는다 (TASK_QUIZ_REQUIRED). 완료 취소는 그대로 된다.
     * 지금 상태와 같은 값을 보내는 건 막지 않는다.
     */
    fun completeTask(userId: Int, taskId: Int, isCompleted: Boolean): PlanTaskResponse = transaction {
        val task = PlanTaskRow.findById(taskId)
            ?: throw PlanTaskNotFoundException()

        if (task.dailyPlan.planBoard.user.id.value != userId) {
            throw PlanTaskNotFoundException()
        }

        if (task.isCompleted != isCompleted && isFinalizedByQuiz(taskId)) {
            throw PlanTaskValidationException.TaskFinalizedException()
        }

        // 퀴즈를 통과하면 TaskQuizService.submitQuiz 가 완료로 바꾼다. 여기서 완료로 바꾸면 퀴즈 없이 잔디가 채워진다 (#259)
        if (isCompleted && !task.isCompleted) {
            throw PlanTaskValidationException.TaskQuizRequiredException()
        }

        task.isCompleted = isCompleted
        task.toResponse(planBoardIdOf(task), isManualPlanBoard(task.dailyPlan.planBoard.examDate), subjectNameOf(task))
    }

    /** 태스크 퀴즈를 통과했거나, 마지막 차수(MAX_ATTEMPTS)까지 불합격해 결과가 확정된 태스크인지 */
    private fun isFinalizedByQuiz(taskId: Int): Boolean =
        !TaskQuizAttemptTable.selectAll().where {
            (TaskQuizAttemptTable.planTaskId eq taskId) and (
                (TaskQuizAttemptTable.passed eq true) or
                    ((TaskQuizAttemptTable.passed eq false) and (TaskQuizAttemptTable.attemptNumber greaterEq MAX_ATTEMPTS))
                )
        }.empty()

    /** taskName/startTime/endTime/estimatedMinutes 중 전달된 필드만 수정 (본인 플랜보드 소유권 확인) */
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
        // 예상 시간은 태스크 퀴즈 문항 수·캘린더 공부 시간 합계를 정한다. 시간만 바꾸고 예상 시간을 안 보내면
        // 옛 값이 남아(1시간 → 30분으로 줄여도 퀴즈는 1시간 기준) 바뀐 시간 길이로 맞춘다. 보내면 그 값을 쓴다
        val timeChanged = request.startTime != null || request.endTime != null
        val newEstimatedMinutes = request.estimatedMinutes
            ?: if (timeChanged) Duration.between(newStartTime, newEndTime).toMinutes().toInt() else task.estimatedMinutes
        if (newEstimatedMinutes < 1) throw PlanTaskValidationException.InvalidEstimatedMinutesException()

        // 퀴즈로 결과가 확정된 할일은 지난 기록이라 시간·예상 시간을 못 바꾼다 (완료 상태 변경과 같은 규칙, completeTask).
        // 이름 변경과 지금과 같은 값을 다시 보내는 것은 막지 않는다 — 프론트는 수정 시 모든 필드를 함께 보낸다
        val scheduleChanged = newStartTime != task.startTime || newEndTime != task.endTime || newEstimatedMinutes != task.estimatedMinutes
        if (scheduleChanged && isFinalizedByQuiz(taskId)) {
            throw PlanTaskValidationException.TaskFinalizedException()
        }

        task.startTime = newStartTime
        task.endTime = newEndTime
        task.estimatedMinutes = newEstimatedMinutes

        task.toResponse(planBoardIdOf(task), isManualPlanBoard(task.dailyPlan.planBoard.examDate), subjectNameOf(task))
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

    /** 과목명은 호출부가 채워 넘긴다 — 여기서 조회하면 응답 행마다 SELECT 가 나간다 */
    private fun PlanTaskRow.toResponse(planBoardId: Int, isManual: Boolean, subjectName: String? = null) = PlanTaskResponse(
        id = id.value,
        dailyPlanId = readValues[PlanTaskTable.dailyPlanId].value,
        planBoardId = planBoardId,
        isManual = isManual,
        taskName = taskName,
        startTime = startTime.format(timeFormat),
        endTime = endTime.format(timeFormat),
        estimatedMinutes = estimatedMinutes,
        isCompleted = isCompleted,
        subjectId = readValues[PlanTaskTable.subjectId]?.value,
        subjectName = subjectName
    )

    /** 단건 응답용 — 한 행뿐이라 과목을 바로 조회한다 */
    private fun subjectNameOf(row: PlanTaskRow): String? =
        row.subjectId?.value?.let { SubjectRow.findById(it)?.name }

    /** 단건 응답용 — 이미 읽은 일일 계획 행에서 보드 id 만 꺼낸다 (보드 조회 SELECT 를 더 내지 않는다) */
    private fun planBoardIdOf(row: PlanTaskRow): Int =
        row.dailyPlan.readValues[DailyPlanTable.planBoardId].value
}

/** 여러 보드가 섞인 목록 조회에서 할일 한 행과 그 보드의 id·직접 만든 보드 여부 */
private data class BoardTask(val planBoardId: Int, val isManual: Boolean, val task: PlanTaskRow)
