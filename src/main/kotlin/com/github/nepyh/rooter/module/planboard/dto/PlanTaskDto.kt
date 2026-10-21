package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanTaskResponse(
    val id: Int,
    val dailyPlanId: Int,         // 피드백·챗봇(/daily-plans/{dailyPlanId}/...) 호출용
    val planBoardId: Int,         // 이 할일이 속한 플랜보드 ID. 여러 보드가 섞이는 조회에서 보드 구분용
    val taskName: String,
    val startTime: String,        // "17:30"
    val endTime: String,          // "19:30"
    val estimatedMinutes: Int,    // "2시간" 표시용
    val isCompleted: Boolean,
    // 할일 목록을 과목별로 분류해 제목으로 보여주기 위한 값. null = 과목 미지정(파생 할일·컬럼 추가 전 행)
    val subjectId: Int? = null,
    val subjectName: String? = null
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
    val estimatedMinutes: Int,
    // 수동 일정 생성에서 프론트가 고른 교과서. 오면 서버가 교과서로부터 과목을 유도해 할일에 붙인다
    val textbookId: Int? = null
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
