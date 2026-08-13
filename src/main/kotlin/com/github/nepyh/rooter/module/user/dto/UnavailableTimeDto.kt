package com.github.nepyh.rooter.module.user.dto

import com.github.nepyh.rooter.module.user.model.DayOfWeek
import kotlinx.serialization.Serializable


@Serializable
data class UnavailableTimeRequest(
    val dayOfWeek: Short,
    val startTime: String,
    val endTime: String
)

@Serializable
data class UnavailableTimeResponse(
    val id: Int,
    val dayOfWeek: DayOfWeek,
    val startTime: String,
    val endTime: String
)
