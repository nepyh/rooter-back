package com.github.nepyh.rooter.module.chat.dto

import com.github.nepyh.rooter.module.chat.model.ChatTurnFeedback
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageRequest(
    val message: String,
    val sessionId: Int? = null // 없으면 이 날 계획으로 마지막에 대화한 세션, 그것도 없으면 새 세션 (#342)
)

@Serializable
data class ChatTurnResponse(
    val id: Int,
    val sessionId: Int,
    val dailyPlanId: Int, // 이 메시지로 보고 고친 날의 계획
    val role: String,
    val content: String,
    val feedback: ChatTurnFeedback? = null, // 챗봇 답변의 좋아요·싫어요. 사용자 메시지는 항상 null
    val createdAt: String
)

@Serializable
data class ChatMessageResponse(
    val reply: String,
    val planChanged: Boolean,
    val updatedTasks: List<PlanTaskResponse>? = null, // 대화한 날의 바뀐 할일 목록 (미뤘으면 남은 할일)
    val movedTasks: List<PlanTaskResponse>? = null, // 다른 날로 미룬 할일 (옮겨간 날의 dailyPlanId·시각)
    val updatedDays: List<ChatUpdatedDay>? = null, // 대화한 날 말고 바뀐 다른 날들 — 다른 날 일정(#334)·다른 날 할일 편집·미루기(#337)
    val sessionId: Int? = null, // 이 대화가 저장된 세션 (#342)
    val sessionTitle: String? = null, // 세션 제목. 첫 메시지에서 정해진다
    val userTurnId: Int? = null, // 저장된 사용자 메시지 id (삭제용)
    val assistantTurnId: Int? = null // 저장된 챗봇 답변 id (좋아요·싫어요, 삭제용)
)

/** 챗봇으로 할일이 바뀐 다른 날. tasks 는 그날(같은 플랜보드) 할일 전체 */
@Serializable
data class ChatUpdatedDay(
    val date: String, // "yyyy-MM-dd"
    val dailyPlanId: Int,
    val tasks: List<PlanTaskResponse>
)

@Serializable
data class ChatSessionResponse(
    val id: Int,
    val title: String?, // 첫 메시지 전에는 null
    val createdAt: String,
    val updatedAt: String // 마지막 대화 시각
)

@Serializable
data class ChatTurnFeedbackRequest(
    val feedback: ChatTurnFeedback? = null // null 이면 취소
)
