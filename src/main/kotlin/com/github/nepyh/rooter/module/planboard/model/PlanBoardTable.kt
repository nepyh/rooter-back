package com.github.nepyh.rooter.module.planboard.model

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PlanBoardTable : IntIdTable("plan_boards") {
    val userId = reference("user_id", UserTable)
    val title = varchar("title", 100)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val createdAt = timestampWithTimeZone("created_at")
}

class PlanBoardRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanBoardRow>(PlanBoardTable)

    var user by UserRow referencedOn PlanBoardTable.userId
    var title by PlanBoardTable.title
    var startDate by PlanBoardTable.startDate
    var endDate by PlanBoardTable.endDate
    var createdAt by PlanBoardTable.createdAt
}
