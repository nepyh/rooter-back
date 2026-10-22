package com.github.nepyh.rooter.module.feedback.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object DailyFeedbackTable : IntIdTable("daily_feedback") {
    val dailyPlanId = reference("daily_plan_id", DailyPlanTable)
    // DDL 의 fk_daily_feedback_plan_task 와 동일 (on delete cascade). 설문은 할일 단위이고,
    // 할일이 지워지면 그 할일의 설문도 함께 지워진다.
    // nullable: DDL 의 uq_daily_feedback_plan_task 는 NULL 을 서로 다른 값으로 보므로,
    // 이 컬럼 추가 전에 만들어진 기존 행(하루 1건이던 시절)은 NULL 로 남는다.
    val planTaskId = optReference("plan_task_id", PlanTaskTable, onDelete = ReferenceOption.CASCADE)
    val difficulty = varchar("difficulty", 10) // DDL 스펙: CHECK ('쉬움', '적당', '어려움')
    val timeSpentMinutes = integer("time_spent_minutes").nullable()
    val focusLevel = integer("focus_level").nullable() // DDL 스펙: smallint
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone) // DDL: timestamptz
}

class DailyFeedbackRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DailyFeedbackRow>(DailyFeedbackTable)

    var dailyPlan by DailyPlanRow referencedOn DailyFeedbackTable.dailyPlanId
    // FK id 만 쓰는 컬럼이라 참조 엔티티 대신 EntityID 로 둔다 (부모 행 lazy SELECT 방지).
    // 할일 내용이 필요하면 PlanTaskRow.findById(taskId) 로 따로 읽는다.
    var planTaskId by DailyFeedbackTable.planTaskId
    var difficulty by DailyFeedbackTable.difficulty
    var timeSpentMinutes by DailyFeedbackTable.timeSpentMinutes
    var focusLevel by DailyFeedbackTable.focusLevel
    var createdAt by DailyFeedbackTable.createdAt
}
