package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanBoardCreateRequest(
    val title: String,
    val startDate: String,
    val endDate: String
)

@Serializable
data class PlanBoardResponse(
    val id: Int,
    val title: String,
    val startDate: String,
    val endDate: String,
    val createdAt: String
)

@Serializable
data class PlanBoardCreateResponse(
    val id: Int,
    val message: String
)
