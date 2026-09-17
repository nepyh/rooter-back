package com.github.nepyh.rooter.module.chat.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// DDL 에 없는 테이블 (반영 필요, 별도 전달함): 하루 계획(daily_plan)별 챗봇 대화 이력.
object ChatTurns : Table("chat_turns") {
    val id = integer("id").autoIncrement()
    val dailyPlanId = integer("daily_plan_id").references(DailyPlanTable.id)
    val role = varchar("role", 10) // "user" | "assistant"
    val content = text("content")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
