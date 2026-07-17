package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date

object DailyPlans : Table("daily_plans") {
    val id = integer("id").autoIncrement()
    val planBoardId = integer("plan_board_id") references PlanBoards.id
    val planDate = date("plan_date")

    override val primaryKey = PrimaryKey(id)
}
