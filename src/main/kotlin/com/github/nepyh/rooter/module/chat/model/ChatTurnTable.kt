package com.github.nepyh.rooter.module.chat.model

import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * 챗봇 대화 한 턴. daily_plan_id 는 그 메시지로 어느 날 계획을 보고 고쳤는지 — 계획 없이 나눈 대화면 null (#351),
 * session_id 는 어느 대화에 속하는지 (#342).
 */
object ChatTurnTable : IntIdTable("chat_turns") {
    val dailyPlanId = reference("daily_plan_id", DailyPlanTable).nullable()
    val sessionId = reference("session_id", ChatSessionTable, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 10) // "user" | "assistant"
    val content = text("content")
    val feedback = enumerationByName("feedback", 10, ChatTurnFeedback::class).nullable() // 챗봇 답변에만
    val createdAt = timestampWithTimeZone("created_at")
}

class ChatTurnRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChatTurnRow>(ChatTurnTable)

    var dailyPlan by DailyPlanRow optionalReferencedOn ChatTurnTable.dailyPlanId
    var session by ChatSessionRow referencedOn ChatTurnTable.sessionId
    val dailyPlanKey by ChatTurnTable.dailyPlanId // 조회 없이 id 만 쓸 때
    val sessionKey by ChatTurnTable.sessionId
    var role by ChatTurnTable.role
    var content by ChatTurnTable.content
    var feedback by ChatTurnTable.feedback
    var createdAt by ChatTurnTable.createdAt
}

/** 챗봇 답변에 남기는 좋아요·싫어요 (#342). DB(chat_turns.feedback)에는 이름 그대로 저장한다 */
@Serializable
enum class ChatTurnFeedback {
    LIKE, DISLIKE
}
