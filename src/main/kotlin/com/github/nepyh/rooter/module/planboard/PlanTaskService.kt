package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object DailyPlans : Table("daily_plans") {
    val id = integer("id").autoIncrement()
    val planBoardId = integer("plan_board_id") references PlanBoards.id
    val planDate = date("plan_date")

    override val primaryKey = PrimaryKey(id)
}

object PlanTasks : Table("plan_tasks") {
    val id = integer("id").autoIncrement()
    val dailyPlanId = integer("daily_plan_id") references DailyPlans.id
    val taskName = varchar("task_name", 150)
    val startTime = time("start_time")   // DDL 스펙: time
    val endTime = time("end_time")
    val estimatedMinutes = integer("estimated_minutes")
    val isCompleted = bool("is_completed").default(false)

    override val primaryKey = PrimaryKey(id)
}

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

    suspend fun createTask(request: PlanTaskCreateRequest) = newSuspendedTransaction {
        val board = PlanBoards.selectAll()
            .where { PlanBoards.id eq request.planBoardId }
            .firstOrNull()
            ?: throw ApiException(ErrorCode.BOARD_004)

        if (request.taskName.isBlank() || request.taskName.length > 150) {
            throw ApiException(ErrorCode.TASK_001)
        }

        val date = runCatching { LocalDate.parse(request.planDate) }
            .getOrElse { throw ApiException(ErrorCode.TASK_002) }

        val startTime = runCatching { LocalTime.parse(request.startTime) }
            .getOrElse { throw ApiException(ErrorCode.TASK_003) }
        val endTime = runCatching { LocalTime.parse(request.endTime) }
            .getOrElse { throw ApiException(ErrorCode.TASK_003) }

        if (request.estimatedMinutes < 1) {
            throw ApiException(ErrorCode.TASK_004)
        }

        if (date.isBefore(board[PlanBoards.startDate]) || date.isAfter(board[PlanBoards.endDate])) {
            throw ApiException(ErrorCode.TASK_005)
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