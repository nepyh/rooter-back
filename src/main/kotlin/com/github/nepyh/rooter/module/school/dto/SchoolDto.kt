package com.github.nepyh.rooter.module.school.dto

import kotlinx.serialization.Serializable

@Serializable
data class SchoolSearchResponse(
    val schoolId: String, // student_profiles.school_id 에 그대로 저장할 합성 식별자
    val name: String,
    val officeName: String,
    val region: String,
    val foundation: String?
)

@Serializable
data class SchoolExamScheduleResponse(
    val date: String, // "2026-07-01"
    val name: String   // NICE 학사일정 원본 이벤트명 (예: "1학기 기말고사") — 정확한 분류 아닌 추천 후보
)
