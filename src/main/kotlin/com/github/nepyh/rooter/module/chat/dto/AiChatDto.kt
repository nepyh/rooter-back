package com.github.nepyh.rooter.module.chat.dto

import kotlinx.serialization.Serializable

/** AI가 돌려주는 챗봇 응답 JSON을 그대로 역직렬화하기 위한 wire 전용 DTO. */

@Serializable
data class AiChatTask(
    val task_name: String,
    val estimated_minutes: Int
)

/**
 * 프롬프트 <CURRENT_TASKS> 로 보내는 현재 태스크. AI 가 새 일정(busy window)이 기존 태스크와
 * 겹치는지 판단할 수 있도록 시각을 함께 보낸다. (응답의 plan_update.tasks 는 [AiChatTask] 그대로 — 시각은 서버가 배정)
 */
@Serializable
data class AiChatCurrentTask(
    val task_name: String,
    val estimated_minutes: Int,
    val start_time: String, // "HH:mm"
    val end_time: String
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
