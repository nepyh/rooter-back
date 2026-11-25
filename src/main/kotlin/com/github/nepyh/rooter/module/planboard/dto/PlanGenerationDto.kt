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
    // 시험 마지막 날 "yyyy-MM-dd" (#376). examDate 가 있어야 하고 그보다 앞설 수 없다. 저장·표시용이라 계획 기간은 바뀌지 않는다(시험 첫날 전날까지)
    val examEndDate: String? = null,
    val daysRemaining: Int? = null, // examDate 를 안 주면 이 값으로 종료일을 계산. 둘 다 없으면 에러
    val targetScore: Int? = null,
    val isCramMode: Boolean = false
)

@Serializable
data class PlanGenerationTaskResponse(
    val taskName: String,
    val estimatedMinutes: Int,
    val startTime: String,
    val endTime: String,
    // 할일 조회 API(PlanTaskResponse)와 같은 값. null = AI 가 요청 과목 중 하나로 표기하지 않음
    val subjectId: Int? = null,
    val subjectName: String? = null
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
    val examEndDate: String?,
    val isCramMode: Boolean,
    val dailyPlans: List<PlanGenerationDailyResponse>, // 지금까지 만든 날만 (앞 구간). 나머지는 만들어지는 대로 일일 계획 조회로
    val tips: List<String>,
    // AI 계획 생성 상태 (#405): COMPLETED = 기간 전체를 만듦, GENERATING = 뒷부분을 서버가 이어서 만드는 중
    val generationStatus: String = "COMPLETED",
    val generatedUntil: String? = null // 지금까지 만든 마지막 날짜 "yyyy-MM-dd". COMPLETED 면 endDate 와 같다
)

/** POST /plan-generation/{planBoardId}/retry 응답 (#405) */
@Serializable
data class PlanGenerationRetryResponse(
    val planBoardId: Int,
    val generationStatus: String, // GENERATING
    val generatedUntil: String // 이미 만든 마지막 날짜. 그 다음 날부터 다시 만든다
)
