package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.time

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
