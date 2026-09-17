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
    val examDate = date("exam_date").nullable() // 실제 시험 날짜 (end_date 와 별개일 수 있음)
    val isCramMode = bool("is_cram_mode").default(false)
    val createdAt = timestampWithTimeZone("created_at")
}

class PlanBoardRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanBoardRow>(PlanBoardTable)

    var user by UserRow referencedOn PlanBoardTable.userId
    var title by PlanBoardTable.title
    var startDate by PlanBoardTable.startDate
    var endDate by PlanBoardTable.endDate
    var examDate by PlanBoardTable.examDate
    var isCramMode by PlanBoardTable.isCramMode
    var createdAt by PlanBoardTable.createdAt
}
