package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanGenerationSubjectInput(
    val textbookId: Int,
    val startChapterId: Int,
    val endChapterId: Int,
    val customRangeText: String? = null
)

@Serializable
data class PlanGenerationRequest(
    val title: String,
    val subjects: List<PlanGenerationSubjectInput>,
    val startDate: String? = null, // "yyyy-MM-dd", 생략하면 오늘
    val examDate: String? = null, // "yyyy-MM-dd", 주어지면 daysRemaining을 여기서 자동 계산 (시험 당일은 공부일에서 제외)
    val daysRemaining: Int? = null, // examDate 를 안 주면 이 값으로 종료일을 계산. 둘 다 없으면 에러
    val targetScore: Int? = null,
    val isCramMode: Boolean = false
)

@Serializable
data class PlanGenerationTaskResponse(
    val taskName: String,
    val estimatedMinutes: Int,
    val startTime: String,
    val endTime: String
)

@Serializable
data class PlanGenerationDailyResponse(
    val dailyPlanId: Int,
    val date: String,
    val topics: List<String>,
    val goal: String,
    val tasks: List<PlanGenerationTaskResponse>
)

@Serializable
data class PlanGenerationResponse(
    val planBoardId: Int,
    val title: String,
    val startDate: String,
    val endDate: String,
    val examDate: String?,
    val isCramMode: Boolean,
    val dailyPlans: List<PlanGenerationDailyResponse>,
    val tips: List<String>
)
