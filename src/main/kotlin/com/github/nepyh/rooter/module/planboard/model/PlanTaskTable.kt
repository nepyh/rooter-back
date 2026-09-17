package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.time

object PlanTaskTable : IntIdTable("plan_tasks") {
    // DDL 의 fk_plan_tasks_daily_plan 과 동일 (on delete cascade)
    val dailyPlanId = reference("daily_plan_id", DailyPlanTable, onDelete = ReferenceOption.CASCADE)
    val taskName = varchar("task_name", 150)
    val startTime = time("start_time")
    val endTime = time("end_time")
    val estimatedMinutes = integer("estimated_minutes")
    val isCompleted = bool("is_completed").default(false)
}

class PlanTaskRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanTaskRow>(PlanTaskTable)

    var dailyPlan by DailyPlanRow referencedOn PlanTaskTable.dailyPlanId
    var taskName by PlanTaskTable.taskName
    var startTime by PlanTaskTable.startTime
    var endTime by PlanTaskTable.endTime
    var estimatedMinutes by PlanTaskTable.estimatedMinutes
    var isCompleted by PlanTaskTable.isCompleted
}
