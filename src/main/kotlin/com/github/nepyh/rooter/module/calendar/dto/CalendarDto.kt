package com.github.nepyh.rooter.module.calendar.dto

import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import kotlinx.serialization.Serializable

@Serializable
data class CalendarDayResponse(
    val date: String,           // "2026-07-01"
    val plannedMinutes: Int     // 그 날 계획된 태스크들의 예상 소요 시간 합
)

@Serializable
data class CalendarExamResponse(
    val planBoardId: Int,
    val title: String,
    val examDate: String,
    val dDay: Int                // 오늘 기준 남은 일수 (지났으면 음수)
)

@Serializable
data class CalendarRangeResponse(
    val days: List<CalendarDayResponse>,
    val exams: List<CalendarExamResponse>,
    val events: List<CalendarEventResponse>
)

@Serializable
data class DailyCompletionResponse(
    val date: String,
    val totalTasks: Int,
    val completedTasks: Int,
    val completionRate: Double,  // 0~100
    val tasks: List<PlanTaskResponse>,
    val events: List<CalendarEventResponse>
)

@Serializable
data class CalendarEventCreateRequest(
    val title: String,
    val eventDate: String,     // "2026-07-01"
    val memo: String? = null
)

@Serializable
data class CalendarEventResponse(
    val id: Int,
    val title: String,
    val eventDate: String,
    val memo: String?
)
