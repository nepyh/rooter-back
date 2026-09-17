package com.github.nepyh.rooter.module.chat.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): 하루 계획(daily_plan)별 챗봇 대화 이력.
object ChatTurnTable : IntIdTable("chat_turns") {
    val dailyPlanId = reference("daily_plan_id", DailyPlanTable)
    val role = varchar("role", 10) // "user" | "assistant"
    val content = text("content")
    val createdAt = timestampWithTimeZone("created_at")
}

class ChatTurnRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChatTurnRow>(ChatTurnTable)

    var dailyPlan by DailyPlanRow referencedOn ChatTurnTable.dailyPlanId
    var role by ChatTurnTable.role
    var content by ChatTurnTable.content
    var createdAt by ChatTurnTable.createdAt
}
