package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class PlanTaskService {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun getDailyPlan(userId: Int, date: LocalDate): DailyPlanResponse =
        newSuspendedTransaction {
            val user = UserRow.findById(userId)
                ?: return@newSuspendedTransaction DailyPlanResponse(planDate = date.toString(), tasks = emptyList())

            val tasks = (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where { (DailyPlanTable.planDate eq date) and (PlanBoardTable.userId eq user.id) }
                .orderBy(PlanTaskTable.startTime)
                .map { PlanTaskRow.wrapRow(it) }
                .map {
                    PlanTaskResponse(
                        id = it.id.value,
                        taskName = it.taskName,
                        startTime = it.startTime.format(timeFormat),
                        endTime = it.endTime.format(timeFormat),
                        estimatedMinutes = it.estimatedMinutes,
                        isCompleted = it.isCompleted
                    )
                }

            DailyPlanResponse(planDate = date.toString(), tasks = tasks)
        }

    suspend fun createTask(userId: Int, request: PlanTaskCreateRequest) = newSuspendedTransaction {
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

        if (request.estimatedMinutes < 1) {
            throw PlanTaskValidationException.InvalidEstimatedMinutesException()
        }

        if (date.isBefore(board.startDate) || date.isAfter(board.endDate)) {
            throw PlanTaskValidationException.PlanDateOutOfRangeException()
        }

        val dailyPlan = DailyPlanRow.find {
            (DailyPlanTable.planBoardId eq board.id) and (DailyPlanTable.planDate eq date)
        }.firstOrNull()
            ?: DailyPlanRow.new {
                planBoard = board
                planDate = date
            }

        PlanTaskRow.new {
            this.dailyPlan = dailyPlan
            taskName = request.taskName
            this.startTime = startTime
            this.endTime = endTime
            estimatedMinutes = request.estimatedMinutes
        }
    }
}
