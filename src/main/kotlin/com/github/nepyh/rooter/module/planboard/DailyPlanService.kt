package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate


class DailyPlanService {

    suspend fun getDailyPlan(userId: Int, boardId: Int, targetDate: LocalDate): DailyPlanResponse = newSuspendedTransaction {
        // 0. 플랜보드 존재 + 소유권 확인
        val board = PlanBoardRow.findById(boardId)
            ?: throw PlanBoardNotFoundException()

        if (board.user.id.value != userId) {
            throw PlanBoardForbiddenException()
        }

        // 1. boardId + date 로 해당 daily_plan 찾기
        val dailyPlan = DailyPlanRow.find {
            (DailyPlanTable.planBoardId eq board.id) and (DailyPlanTable.planDate eq targetDate)
        }.firstOrNull()

        // 2. 없으면 빈 리스트 반환
        if (dailyPlan == null) {
            return@newSuspendedTransaction DailyPlanResponse(
                planDate = targetDate.toString(),    // date → planDate
                tasks = emptyList()
            )
        }

        // 3. 그 daily_plan 의 task 들 조회
        val tasks = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlan.id }
            .orderBy(PlanTaskTable.startTime to SortOrder.ASC)
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

        // 마지막 반환
        DailyPlanResponse(planDate = targetDate.toString(), tasks = tasks)   // date → planDate
    }
}
