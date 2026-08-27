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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

        DailyPlanResponse(planDate = targetDate.toString(), tasks = tasks)
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

    private fun PlanTaskRow.toResponse() = PlanTaskResponse(
        id = id.value,
        taskName = taskName,
        startTime = startTime.format(timeFormat),
        endTime = endTime.format(timeFormat),
        estimatedMinutes = estimatedMinutes,
    isCompleted = isCompleted
    )
}
