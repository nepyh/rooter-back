package com.github.nepyh.rooter.module.planboard.dto

import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizResponse
import kotlinx.serialization.Serializable

/**
 * 완료 버튼(PATCH /plan-tasks/{taskId}/complete) 응답. PlanTaskResponse 와 같은 필드에 quiz 가 더해진다.
 * 완료는 퀴즈를 통과해야 되므로, isCompleted=true 로 보내면 보통 isCompleted 는 그대로 false 이고 풀 quiz 가 온다.
 */
@Serializable
data class PlanTaskCompleteResponse(
    val id: Int,
    val dailyPlanId: Int,
    val taskName: String,
    val startTime: String,
    val endTime: String,
    val estimatedMinutes: Int,
    val isCompleted: Boolean,
    val quiz: TaskQuizResponse? = null // 풀어야 할 완료 확인 퀴즈. 완료 취소이거나 이미 완료(통과)된 태스크면 null
)

@Serializable
data class PlanTaskResponse(
    val id: Int,
    val dailyPlanId: Int,         // 피드백·챗봇(/daily-plans/{dailyPlanId}/...) 호출용
    val taskName: String,
    val startTime: String,        // "17:30"
    val endTime: String,          // "19:30"
    val estimatedMinutes: Int,    // "2시간" 표시용
    val isCompleted: Boolean
)

@Serializable
data class DailyPlanResponse(
    // 플랜보드 하나 기준 조회(/plan-boards/{id}/daily)에서만 채움. 여러 보드를 합치는 조회(/plan-tasks, /week)는
    // 한 날짜에 일일 계획이 여러 개일 수 있어 null — 각 태스크의 dailyPlanId 를 쓸 것
    val dailyPlanId: Int? = null,
    val planDate: String,         // "2026-06-30"
    val tasks: List<PlanTaskResponse>
)

@Serializable
data class WeeklyPlanResponse(
    val weekStart: String,        // 월요일, "2026-06-29"
    val weekEnd: String,          // 일요일, "2026-07-05"
    val days: List<DailyPlanResponse>
)

@Serializable
data class PlanTaskCreateRequest(
    val planBoardId: Int,
    val planDate: String,
    val taskName: String,
    val startTime: String,
    val endTime: String,
    val estimatedMinutes: Int
)

@Serializable
data class PlanTaskCreateResponse(
    val message: String
)

@Serializable
data class PlanTaskCompleteRequest(
    val isCompleted: Boolean
)

@Serializable
data class PlanTaskUpdateRequest(
    val taskName: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val estimatedMinutes: Int? = null
)
