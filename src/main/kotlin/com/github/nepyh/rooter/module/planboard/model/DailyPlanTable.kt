package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.date

object DailyPlanTable : IntIdTable("daily_plans") {
    // DDL 의 fk_daily_plans_board 와 동일 (on delete cascade)
    val planBoardId = reference("plan_board_id", PlanBoardTable, onDelete = ReferenceOption.CASCADE)
    val planDate = date("plan_date")
}

class DailyPlanRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DailyPlanRow>(DailyPlanTable)

    var planBoard by PlanBoardRow referencedOn DailyPlanTable.planBoardId
    var planDate by DailyPlanTable.planDate
}
