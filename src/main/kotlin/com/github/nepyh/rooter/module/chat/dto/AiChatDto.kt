package com.github.nepyh.rooter.module.chat.dto

import kotlinx.serialization.Serializable

/** AI가 돌려주는 챗봇 응답 JSON을 그대로 역직렬화하기 위한 wire 전용 DTO. */

@Serializable
data class AiChatTask(
    val task_name: String,
    val estimated_minutes: Int
)

@Serializable
data class AiChatPlanUpdate(
    val tasks: List<AiChatTask>,
    val busy_window_start: String? = null, // "HH:mm"
    val busy_window_end: String? = null
)

@Serializable
data class AiChatResult(
    val reply_message: String,
    val plan_changed: Boolean = false,
    val plan_update: AiChatPlanUpdate? = null
)

/** 프롬프트에 <CHAT_HISTORY_JSON> 으로 그대로 실어 보내기 위한 대화 턴 wire 포맷. */
@Serializable
data class AiChatTurn(
    val role: String,
    val content: String
)
