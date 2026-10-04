package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import java.time.LocalDate

/**
 * 사용자의 기간 안 할일(모든 플랜보드) 시간을 날짜별 (시작분, 끝분) 으로 모은다.
 * 새 할일을 배치할 때 기존 할일과 겹치지 않게 하려고 쓴다 ([PlanTaskScheduler.withExistingTasks]). 트랜잭션 안에서 부른다.
 */
fun userTaskRanges(userId: Int, startDate: LocalDate, endDate: LocalDate): Map<LocalDate, List<Pair<Int, Int>>> =
    (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
        .select(DailyPlanTable.planDate, PlanTaskTable.startTime, PlanTaskTable.endTime)
        .where {
            (PlanBoardTable.userId eq userId) and
                (DailyPlanTable.planDate greaterEq startDate) and
                (DailyPlanTable.planDate lessEq endDate)
        }
        .groupBy(
            { it[DailyPlanTable.planDate] },
            { PlanTaskScheduler.toMinutes(it[PlanTaskTable.startTime]) to PlanTaskScheduler.toMinutes(it[PlanTaskTable.endTime]) }
        )
