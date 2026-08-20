package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanBoardCreateRequest(
    val title: String,
    val startDate: String,  // 👈 content 지우고 이 두 줄로 교체!
    val endDate: String
)

@Serializable
data class PlanBoardResponse(
    val id: Int,
    val title: String,
    val content: String,
    val createdAt: String
)

@Serializable
data class PlanBoardCreateResponse(
    val id: Int,
    val message: String
)