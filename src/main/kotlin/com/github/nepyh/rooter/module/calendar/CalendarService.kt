package com.github.nepyh.rooter.module.calendar

import com.github.nepyh.rooter.module.calendar.dto.CalendarDayResponse
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventCreateRequest
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventResponse
import com.github.nepyh.rooter.module.calendar.dto.CalendarExamResponse
import com.github.nepyh.rooter.module.calendar.dto.CalendarRangeResponse
import com.github.nepyh.rooter.module.calendar.dto.DailyCompletionResponse
import com.github.nepyh.rooter.module.calendar.exception.CalendarEventNotFoundException
import com.github.nepyh.rooter.module.calendar.exception.CalendarValidationException
import com.github.nepyh.rooter.module.calendar.model.CalendarEventTable
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
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

            val exams = PlanBoardTable.selectAll()
                .where {
                    (PlanBoardTable.userId eq userId) and
                        (PlanBoardTable.examDate.isNotNull()) and
                        (PlanBoardTable.examDate greaterEq start) and
                        (PlanBoardTable.examDate lessEq end)
                }
                .map {
                    val examDate = it[PlanBoardTable.examDate]!!
                    CalendarExamResponse(
                        planBoardId = it[PlanBoardTable.id].value,
                        title = it[PlanBoardTable.title],
                        examDate = examDate.toString(),
                        dDay = ChronoUnit.DAYS.between(LocalDate.now(), examDate).toInt()
                    )
                }

            val events = CalendarEventTable.selectAll()
                .where {
                    (CalendarEventTable.userId eq userId) and
                        (CalendarEventTable.eventDate greaterEq start) and
                        (CalendarEventTable.eventDate lessEq end)
                }
                .map { it.toCalendarEventResponse() }

            CalendarRangeResponse(days = days, exams = exams, events = events)
        }
    }

    fun getDaySummary(userId: Int, date: LocalDate): DailyCompletionResponse {
        return transaction {
            val tasks = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where { (PlanBoardTable.userId eq userId) and (DailyPlanTable.planDate eq date) }
                .orderBy(PlanTaskTable.startTime)
                .map {
                    PlanTaskResponse(
                        id = it[PlanTaskTable.id].value,
                        taskName = it[PlanTaskTable.taskName],
                        startTime = it[PlanTaskTable.startTime].toString(),
                        endTime = it[PlanTaskTable.endTime].toString(),
                        estimatedMinutes = it[PlanTaskTable.estimatedMinutes],
                        isCompleted = it[PlanTaskTable.isCompleted]
                    )
                }

            val totalTasks = tasks.size
            val completedTasks = tasks.count { it.isCompleted }
            val completionRate = if (totalTasks == 0) 0.0 else (completedTasks.toDouble() / totalTasks) * 100

            val events = CalendarEventTable.selectAll()
                .where { (CalendarEventTable.userId eq userId) and (CalendarEventTable.eventDate eq date) }
                .map { it.toCalendarEventResponse() }

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
            val id = CalendarEventTable.insert {
                it[this.userId] = userId
                it[title] = request.title
                it[this.eventDate] = eventDate
                it[memo] = request.memo
            } get CalendarEventTable.id

            CalendarEventResponse(id = id.value, title = request.title, eventDate = eventDate.toString(), memo = request.memo)
        }
    }

    fun deleteEvent(userId: Int, eventId: Int) {
        transaction {
            val deletedRows = CalendarEventTable.deleteWhere {
                (CalendarEventTable.id eq eventId) and (CalendarEventTable.userId eq userId)
            }
            if (deletedRows == 0) {
                throw CalendarEventNotFoundException()
            }
        }
    }

    private fun ResultRow.toCalendarEventResponse() = CalendarEventResponse(
        id = this[CalendarEventTable.id].value,
        title = this[CalendarEventTable.title],
        eventDate = this[CalendarEventTable.eventDate].toString(),
        memo = this[CalendarEventTable.memo]
    )
}
