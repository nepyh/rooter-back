package com.github.nepyh.rooter.module.calendar

import com.github.nepyh.rooter.module.calendar.dto.CalendarDayResponse
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventCreateRequest
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventResponse
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
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
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

    fun getRange(userId: Int, start: LocalDate, end: LocalDate): CalendarRangeResponse {
        if (end.isBefore(start)) {
            throw CalendarValidationException.InvalidDateRangeException()
        }

        return transaction {
            // 기간별 예상 학습 시간 합계 — 여러 테이블을 조인한 집계라 DAO 로 표현할 수 없어 Table DSL 을 쓴다.
            val minutesByDate = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where {
                    (PlanBoardTable.userId eq userId) and
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

            val exams = PlanBoardRow.find {
                (PlanBoardTable.userId eq userId) and
                    (PlanBoardTable.examDate.isNotNull()) and
                    (PlanBoardTable.examDate greaterEq start) and
                    (PlanBoardTable.examDate lessEq end)
            }.map {
                val examDate = it.examDate!!
                CalendarExamResponse(
                    planBoardId = it.id.value,
                    title = it.title,
                    examDate = examDate.toString(),
                    dDay = ChronoUnit.DAYS.between(LocalDate.now(), examDate).toInt()
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

    fun getDaySummary(userId: Int, date: LocalDate): DailyCompletionResponse {
        return transaction {
            val tasks = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where { (PlanBoardTable.userId eq userId) and (DailyPlanTable.planDate eq date) }
                .orderBy(PlanTaskTable.startTime)
                .map { PlanTaskRow.wrapRow(it) }
                .map {
                    PlanTaskResponse(
                        id = it.id.value,
                        taskName = it.taskName,
                        startTime = it.startTime.toString(),
                        endTime = it.endTime.toString(),
                        estimatedMinutes = it.estimatedMinutes,
                        isCompleted = it.isCompleted
                    )
                }

            val totalTasks = tasks.size
            val completedTasks = tasks.count { it.isCompleted }
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
