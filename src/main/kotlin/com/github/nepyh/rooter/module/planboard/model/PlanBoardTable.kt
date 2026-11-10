package com.github.nepyh.rooter.module.planboard.model

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PlanBoardTable : IntIdTable("plan_boards") {
    val userId = reference("user_id", UserTable)
    val title = varchar("title", 100)
    // 사용자가 붙이는 설명. 한 유저가 여러 보드를 가질 때 목록에서 구분하는 용도 (#다중 보드)
    val description = text("description").nullable()
    val startDate = date("start_date")
    val endDate = date("end_date")
    val examDate = date("exam_date").nullable() // 실제 시험 날짜 (end_date 와 별개일 수 있음)
    val isCramMode = bool("is_cram_mode").default(false)
    // 보관한 보드. 목록에서 기본으로 빠지고 행은 남는다 (삭제는 daily_plans 까지 cascade 라 파괴적)
    val isArchived = bool("is_archived").default(false)
    val createdAt = timestampWithTimeZone("created_at")
    // 제목·설명·기간·보관을 PATCH 하면 갱신한다 (DDL: timestamptz not null default current_timestamp)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}

class PlanBoardRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanBoardRow>(PlanBoardTable)

    var user by UserRow referencedOn PlanBoardTable.userId
    var title by PlanBoardTable.title
    var description by PlanBoardTable.description
    var startDate by PlanBoardTable.startDate
    var endDate by PlanBoardTable.endDate
    var examDate by PlanBoardTable.examDate
    var isCramMode by PlanBoardTable.isCramMode
    var isArchived by PlanBoardTable.isArchived
    var createdAt by PlanBoardTable.createdAt
    var updatedAt by PlanBoardTable.updatedAt
}
