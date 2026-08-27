package com.github.nepyh.rooter.module.planboard.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.date

object DailyPlanTable : IntIdTable("daily_plans") {
    val planBoardId = reference("plan_board_id", PlanBoardTable)
    val planDate = date("plan_date")
}

class DailyPlanRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DailyPlanRow>(DailyPlanTable)

    var planBoard by PlanBoardRow referencedOn DailyPlanTable.planBoardId
    var planDate by DailyPlanTable.planDate
}
