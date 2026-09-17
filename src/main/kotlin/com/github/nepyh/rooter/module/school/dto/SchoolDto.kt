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
