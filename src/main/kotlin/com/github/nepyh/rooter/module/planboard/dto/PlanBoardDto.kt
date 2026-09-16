package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanBoardCreateRequest(
    val title: String,
    val startDate: String,
    val endDate: String,
    val examDate: String? = null
)

@Serializable
data class PlanBoardResponse(
    val id: Int,
    val title: String,
    val startDate: String,
    val endDate: String,
    val examDate: String?,
    val dDay: Int?, // 오늘 기준 시험까지 남은 일수 (지났으면 음수), examDate 없으면 null
    val createdAt: String
)

@Serializable
data class PlanBoardCreateResponse(
    val id: Int,
    val message: String
)

@Serializable
data class PlanBoardUpdateRequest(
    val title: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val examDate: String? = null
)
