package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class StreakDayResponse(
    val date: String,          // "2026-08-10"
    val completionRate: Double // 0~100, 그 날 태스크 없으면 0
)

@Serializable
data class StreakResponse(
    val days: List<StreakDayResponse>
)
