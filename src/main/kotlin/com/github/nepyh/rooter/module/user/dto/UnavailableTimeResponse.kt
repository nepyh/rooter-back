package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class UnavailableTimeResponse(
    val id: Int,
    val dayOfWeek: Short,
    val startTime: String,
    val endTime: String
)
