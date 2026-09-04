package com.github.nepyh.rooter.module.feedback.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

object DailyFeedbacks : Table("daily_feedback") {
    val id = integer("id").autoIncrement()
    val dailyPlanId = integer("daily_plan_id").references(DailyPlanTable.id)
    val difficulty = varchar("difficulty", 10) // DDL 스펙: CHECK ('쉬움', '적당', '어려움')
    val timeSpentMinutes = integer("time_spent_minutes").nullable()
    val focusLevel = integer("focus_level").nullable() // DDL 스펙: smallint
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
