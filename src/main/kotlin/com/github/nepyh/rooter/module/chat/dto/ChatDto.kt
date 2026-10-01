package com.github.nepyh.rooter.module.chat.dto

import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageRequest(
    val message: String
)

@Serializable
data class ChatTurnResponse(
    val role: String,
    val content: String,
    val createdAt: String
)

@Serializable
data class ChatMessageResponse(
    val reply: String,
    val planChanged: Boolean,
    val updatedTasks: List<PlanTaskResponse>? = null, // 대화한 날의 바뀐 할일 목록 (미뤘으면 남은 할일)
    val movedTasks: List<PlanTaskResponse>? = null // 다른 날로 미룬 할일 (옮겨간 날의 dailyPlanId·시각)
)
