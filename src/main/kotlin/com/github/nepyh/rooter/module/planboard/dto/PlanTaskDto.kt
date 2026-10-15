package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

/** 할일 상태. 앱은 이 값으로 대기·완료·실패를 그린다 (isCompleted 만으로는 대기와 실패를 구분할 수 없음) */
@Serializable
enum class PlanTaskStatus {
    PENDING, DONE, FAILED;

    companion object {
        fun of(isCompleted: Boolean, isFailed: Boolean): PlanTaskStatus = when {
            isFailed -> FAILED
            isCompleted -> DONE
            else -> PENDING
        }
    }
}

@Serializable
data class PlanTaskResponse(
    val id: Int,
    val dailyPlanId: Int,         // 피드백·챗봇(/daily-plans/{dailyPlanId}/...) 호출용
    val taskName: String,
    val startTime: String,        // "17:30"
    val endTime: String,          // "19:30"
    val estimatedMinutes: Int,    // "2시간" 표시용
    val isCompleted: Boolean,
    val status: PlanTaskStatus    // PENDING(대기) / DONE(완료) / FAILED(실패, isCompleted=false)
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
