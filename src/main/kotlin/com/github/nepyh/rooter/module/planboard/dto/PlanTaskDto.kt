package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanTaskResponse(
    val id: Int,
    val taskName: String,
    val startTime: String,        // "17:30"
    val endTime: String,          // "19:30"
    val estimatedMinutes: Int,    // "2시간" 표시용
    val isCompleted: Boolean
)

@Serializable
data class DailyPlanResponse(
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
