package com.github.nepyh.rooter.module.feedback.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

object DailyFeedbackTable : IntIdTable("daily_feedback") {
    val dailyPlanId = reference("daily_plan_id", DailyPlanTable)
    val difficulty = varchar("difficulty", 10) // DDL 스펙: CHECK ('쉬움', '적당', '어려움')
    val timeSpentMinutes = integer("time_spent_minutes").nullable()
    val focusLevel = integer("focus_level").nullable() // DDL 스펙: smallint
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

class DailyFeedbackRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DailyFeedbackRow>(DailyFeedbackTable)

    var dailyPlan by DailyPlanRow referencedOn DailyFeedbackTable.dailyPlanId
    var difficulty by DailyFeedbackTable.difficulty
    var timeSpentMinutes by DailyFeedbackTable.timeSpentMinutes
    var focusLevel by DailyFeedbackTable.focusLevel
    var createdAt by DailyFeedbackTable.createdAt
}
