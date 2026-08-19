package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.model.DailyPlans
import com.github.nepyh.rooter.module.planboard.model.PlanBoards
import com.github.nepyh.rooter.module.planboard.model.PlanTasks
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter


class DailyPlanService {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun getDailyPlan(userId: Int, boardId: Int, targetDate: LocalDate): DailyPlanResponse = newSuspendedTransaction {
        // 0. boardId 가 존재하고 본인 소유인지 확인
        PlanBoards.selectAll()
            .where { (PlanBoards.id eq boardId) and (PlanBoards.userId eq userId) }
            .firstOrNull()
            ?: throw PlanBoardNotFoundException()

        // 1. boardId + date 로 해당 daily_plan 찾기
        val dailyPlan = DailyPlans.selectAll()
            .where { (DailyPlans.planBoardId eq boardId) and (DailyPlans.planDate eq targetDate) }
            .firstOrNull()

        // 2. 없으면 빈 리스트 반환
        if (dailyPlan == null) {
            return@newSuspendedTransaction DailyPlanResponse(
                planDate = targetDate.toString(),    // date → planDate
                tasks = emptyList()
            )
        }

        val dailyPlanId = dailyPlan[DailyPlans.id]

        // 3. 그 daily_plan 의 task 들 조회
        val tasks = PlanTasks.selectAll()
            .where { PlanTasks.dailyPlanId eq dailyPlanId }
            .orderBy(PlanTasks.startTime to SortOrder.ASC)
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

        // 마지막 반환
        DailyPlanResponse(planDate = targetDate.toString(), tasks = tasks)   // date → planDate
    }
}
