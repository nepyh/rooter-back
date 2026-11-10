package com.github.nepyh.rooter.module.calendar

import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.calendar.dto.CalendarDayResponse
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventCreateRequest
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventResponse
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventUpdateRequest
import com.github.nepyh.rooter.module.calendar.dto.CalendarExamResponse
import com.github.nepyh.rooter.module.calendar.dto.CalendarRangeResponse
import com.github.nepyh.rooter.module.calendar.dto.DailyCompletionResponse
import com.github.nepyh.rooter.module.calendar.exception.CalendarEventNotFoundException
import com.github.nepyh.rooter.module.calendar.exception.CalendarValidationException
import com.github.nepyh.rooter.module.calendar.model.CalendarEventRow
import com.github.nepyh.rooter.module.calendar.model.CalendarEventTable
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.isManualPlanBoard
import com.github.nepyh.rooter.module.planboard.subjectNameFrom
import com.github.nepyh.rooter.module.planboard.subjectNamesOf
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CalendarService {

    /** 보드 캘린더 경로의 소유권 확인. 없는 보드는 404, 남의 보드는 403 (플랜보드 모듈과 같은 규칙) */
    private fun requireOwnedBoard(userId: Int, boardId: Int): PlanBoardRow {
        val board = PlanBoardRow.findById(boardId) ?: throw PlanBoardNotFoundException()
        if (board.user.id.value != userId) throw PlanBoardForbiddenException()
        return board
    }

    /**
     * [planBoardId] 를 주면 그 보드의 계획·시험만 집계한다 — 한 유저가 여러 보드를 가질 때 보드 하나의 캘린더를 보기 위한 경로
     * (개인 일정은 사용자 단위라 보드와 무관하게 항상 포함). 보드 소유권은 여기서 확인한다.
     */
    fun getRange(userId: Int, start: LocalDate, end: LocalDate, planBoardId: Int? = null): CalendarRangeResponse {
        if (end.isBefore(start)) {
            throw CalendarValidationException.InvalidDateRangeException()
        }

        return transaction {
            planBoardId?.let { requireOwnedBoard(userId, it) }
            val boardFilter: Op<Boolean> = if (planBoardId == null) {
                PlanBoardTable.userId eq userId
            } else {
                (PlanBoardTable.userId eq userId) and (PlanBoardTable.id eq planBoardId)
            }

            // 기간별 예상 학습 시간 합계 — 여러 테이블을 조인한 집계라 DAO 로 표현할 수 없어 Table DSL 을 쓴다.
            val minutesByDate = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where {
                    boardFilter and
                        (DailyPlanTable.planDate greaterEq start) and
                        (DailyPlanTable.planDate lessEq end)
                }
                .groupBy { it[DailyPlanTable.planDate] }
                .mapValues { (_, rows) -> rows.sumOf { it[PlanTaskTable.estimatedMinutes] } }

            val days = generateSequence(start) { it.plusDays(1) }
                .takeWhile { !it.isAfter(end) }
                .map { date ->
                    CalendarDayResponse(
                        date = date.toString(),
                        plannedMinutes = minutesByDate[date] ?: 0
                    )
                }
                .toList()

            val examFilter: Op<Boolean> = if (planBoardId == null) {
                PlanBoardTable.userId eq userId
            } else {
                PlanBoardTable.id eq planBoardId
            }
            val exams = PlanBoardRow.find {
                examFilter and
                    (PlanBoardTable.examDate.isNotNull()) and
                    (PlanBoardTable.examDate greaterEq start) and
                    (PlanBoardTable.examDate lessEq end)
            }.map {
                val examDate = it.examDate!!
                CalendarExamResponse(
                    planBoardId = it.id.value,
                    title = it.title,
                    examDate = examDate.toString(),
                    dDay = ChronoUnit.DAYS.between(todayInAppZone(), examDate).toInt()
                )
            }

            val events = CalendarEventRow.find {
                (CalendarEventTable.userId eq userId) and
                    (CalendarEventTable.eventDate greaterEq start) and
                    (CalendarEventTable.eventDate lessEq end)
            }.map { it.toCalendarEventResponse() }

            CalendarRangeResponse(days = days, exams = exams, events = events)
        }
    }

    /** [planBoardId] 를 주면 그 보드의 할일만으로 달성률을 계산한다 (개인 일정은 항상 포함) */
    fun getDaySummary(userId: Int, date: LocalDate, planBoardId: Int? = null): DailyCompletionResponse {
        return transaction {
            planBoardId?.let { requireOwnedBoard(userId, it) }
            val boardFilter: Op<Boolean> = if (planBoardId == null) {
                PlanBoardTable.userId eq userId
            } else {
                (PlanBoardTable.userId eq userId) and (PlanBoardTable.id eq planBoardId)
            }

            val rows = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where { boardFilter and (DailyPlanTable.planDate eq date) }
                .orderBy(PlanTaskTable.startTime)
                .toList()
            val taskRows = rows.map { PlanTaskRow.wrapRow(it) }
            val subjectNames = subjectNamesOf(taskRows)
            val tasks = rows.zip(taskRows) { it, row ->
                    PlanTaskResponse(
                        id = row.id.value,
                        dailyPlanId = row.readValues[PlanTaskTable.dailyPlanId].value,
                        planBoardId = it[DailyPlanTable.planBoardId].value,
                        isManual = isManualPlanBoard(it[PlanBoardTable.examDate]),
                        taskName = row.taskName,
                        startTime = row.startTime.toString(),
                        endTime = row.endTime.toString(),
                        estimatedMinutes = row.estimatedMinutes,
                        status = row.status,
                        subjectId = row.subjectId?.value,
                        subjectName = row.subjectNameFrom(subjectNames)
                    )
                }

            val totalTasks = tasks.size
            val completedTasks = tasks.count { it.status == PlanTaskStatus.DONE }
            val completionRate = if (totalTasks == 0) 0.0 else (completedTasks.toDouble() / totalTasks) * 100

            val events = CalendarEventRow.find {
                (CalendarEventTable.userId eq userId) and (CalendarEventTable.eventDate eq date)
            }.map { it.toCalendarEventResponse() }

            DailyCompletionResponse(
                date = date.toString(),
                totalTasks = totalTasks,
                completedTasks = completedTasks,
                completionRate = completionRate,
                tasks = tasks,
                events = events
            )
        }
    }

    fun createEvent(userId: Int, request: CalendarEventCreateRequest): CalendarEventResponse {
        if (request.title.isBlank() || request.title.length > 100) {
            throw CalendarValidationException.InvalidTitleException()
        }

        val eventDate = runCatching { LocalDate.parse(request.eventDate) }
            .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }

        return transaction {
            val event = CalendarEventRow.new {
                this.userId = userId
                this.title = request.title
                this.eventDate = eventDate
                this.memo = request.memo
            }

            CalendarEventResponse(
                id = event.id.value,
                title = request.title,
                eventDate = eventDate.toString(),
                memo = request.memo
            )
        }
    }

    fun updateEvent(userId: Int, eventId: Int, request: CalendarEventUpdateRequest): CalendarEventResponse {
        request.title?.let {
            if (it.isBlank() || it.length > 100) {
                throw CalendarValidationException.InvalidTitleException()
            }
        }

        val eventDate = request.eventDate?.let {
            runCatching { LocalDate.parse(it) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }
        }

        return transaction {
            val event = CalendarEventRow.findById(eventId)?.takeIf { it.userId == userId }
                ?: throw CalendarEventNotFoundException()

            request.title?.let { event.title = it }
            eventDate?.let { event.eventDate = it }
            request.memo?.let { event.memo = it }

            event.toCalendarEventResponse()
        }
    }

    fun deleteEvent(userId: Int, eventId: Int) {
        transaction {
            val event = CalendarEventRow.findById(eventId)?.takeIf { it.userId == userId }
                ?: throw CalendarEventNotFoundException()
            event.delete()
        }
    }

    private fun CalendarEventRow.toCalendarEventResponse() = CalendarEventResponse(
        id = this.id.value,
        title = this.title,
        eventDate = this.eventDate.toString(),
        memo = this.memo
    )
}
