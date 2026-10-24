package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class BusyTimeResponse(
    val startDate: String,
    val endDate: String,
    val days: List<DailyBusyTimeResponse>
)

@Serializable
data class DailyBusyTimeResponse(
    val date: String,
    val isSchoolDay: Boolean, // 평일이면서 공휴일·방학이 아닌 날 (NICE 학사일정 기준, 학교 정보 없으면 평일 전부)
    val busyTimes: List<BusyTimeItem>, // 시작 시각 순
    val freeTimes: List<TimeRangeResponse> // busyTimes 를 전부 뺀 빈 시간
)

@Serializable
data class BusyTimeItem(
    val type: String, // SLEEP(취침) / SCHOOL_PREP(기상~등교 08:30) / SCHOOL(08:30~하교) / COMMUTE(하교 후 귀가) / MEAL(식사) / UNAVAILABLE(사용자가 등록한 불가능 시간) / TASK(기존 할일)
    val label: String, // 앱에 보여줄 이름 — 수면 / 등교 준비 / 학교 / 하교 후 귀가 / 아침·점심·저녁 / 학원·개인 일정(등록한 이름 우선) / 할일 이름
    val startTime: String, // "HH:mm"
    val endTime: String, // "HH:mm", 하루 끝은 "24:00"
    val taskId: Int? = null, // TASK 일 때만
    val taskName: String? = null,
    val planBoardId: Int? = null,
    val planBoardTitle: String? = null
)

@Serializable
data class TimeRangeResponse(
    val startTime: String,
    val endTime: String
)
