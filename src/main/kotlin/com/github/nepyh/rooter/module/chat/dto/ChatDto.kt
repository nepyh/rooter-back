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
    val movedTasks: List<PlanTaskResponse>? = null, // 다른 날로 미룬 할일 (옮겨간 날의 dailyPlanId·시각)
    val updatedDays: List<ChatUpdatedDay>? = null // 대화한 날 말고 다른 날에 생긴 일정으로 바뀐 날들 (#334)
)

/** 다른 날에 생긴 일정으로 할일이 옮겨진 날. tasks 는 그날(같은 플랜보드) 할일 전체 */
@Serializable
data class ChatUpdatedDay(
    val date: String, // "yyyy-MM-dd"
    val dailyPlanId: Int,
    val tasks: List<PlanTaskResponse>
)
