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
    val updatedTasks: List<PlanTaskResponse>? = null
)
