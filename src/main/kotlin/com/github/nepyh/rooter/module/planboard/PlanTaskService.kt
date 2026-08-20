package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.planboard.model.DailyPlans
import com.github.nepyh.rooter.module.planboard.model.PlanBoards
import com.github.nepyh.rooter.module.planboard.model.PlanTasks
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class PlanTaskService {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun getDailyPlan(userId: Int, date: LocalDate): DailyPlanResponse =
        newSuspendedTransaction {
            val tasks = (PlanTasks innerJoin DailyPlans innerJoin PlanBoards)
                .selectAll()
                .where { (DailyPlans.planDate eq date) and (PlanBoards.userId eq userId) }
                .orderBy(PlanTasks.startTime)
                .map {
                    PlanTaskResponse(
                        id = it[PlanTasks.id],
                        taskName = it[PlanTasks.taskName],
                        startTime = it[PlanTasks.startTime].format(timeFormat),
                        endTime = it[PlanTasks.endTime].format(timeFormat),
                        estimatedMinutes = it[PlanTasks.estimatedMinutes],
                        isCompleted = it[PlanTasks.isCompleted]
                    )
                }

            DailyPlanResponse(planDate = date.toString(), tasks = tasks)
        }

    suspend fun createTask(userId: Int, request: PlanTaskCreateRequest) = newSuspendedTransaction {
        val board = PlanBoards.selectAll()
            .where { (PlanBoards.id eq request.planBoardId) and (PlanBoards.userId eq userId) }
            .firstOrNull()
            ?: throw PlanBoardNotFoundException()

        if (request.taskName.isBlank() || request.taskName.length > 150) {
            throw PlanTaskValidationException.InvalidTaskNameException()
        }

        val date = runCatching { LocalDate.parse(request.planDate) }
            .getOrElse { throw PlanTaskValidationException.InvalidPlanDateException() }

        val startTime = runCatching { LocalTime.parse(request.startTime, timeFormat) }
            .getOrElse { throw PlanTaskValidationException.InvalidTimeFormatException() }
        val endTime = runCatching { LocalTime.parse(request.endTime, timeFormat) }
            .getOrElse { throw PlanTaskValidationException.InvalidTimeFormatException() }

        if (!endTime.isAfter(startTime)) {
            throw PlanTaskValidationException.InvalidTimeRangeException()
        }

        if (request.estimatedMinutes < 1) {
            throw PlanTaskValidationException.InvalidEstimatedMinutesException()
        }

        if (date.isBefore(board[PlanBoards.startDate]) || date.isAfter(board[PlanBoards.endDate])) {
            throw PlanTaskValidationException.PlanDateOutOfRangeException()
        }

        val dailyPlanId = DailyPlans.selectAll()
            .where {
                (DailyPlans.planBoardId eq request.planBoardId) and (DailyPlans.planDate eq date)
            }
            .firstOrNull()?.get(DailyPlans.id)
            ?: DailyPlans.insert {
                it[planBoardId] = request.planBoardId
                it[planDate] = date
            } get DailyPlans.id

        PlanTasks.insert {
            it[this.dailyPlanId] = dailyPlanId
            it[taskName] = request.taskName
            it[this.startTime] = startTime
            it[this.endTime] = endTime
            it[estimatedMinutes] = request.estimatedMinutes
        }
    }
}
