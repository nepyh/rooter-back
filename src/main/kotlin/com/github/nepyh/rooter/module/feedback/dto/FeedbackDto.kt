package com.github.nepyh.rooter.module.feedback.dto

import kotlinx.serialization.Serializable

@Serializable
data class FeedbackSubmitRequest(
    val difficulty: String,          // "쉬움" | "적당" | "어려움"
    val timeSpentMinutes: Int? = null,
    val focusLevel: Int? = null      // 1~5
)

@Serializable
data class FeedbackResponse(
    val id: Int,
    val dailyPlanId: Int,
    val difficulty: String,
    val timeSpentMinutes: Int?,
    val focusLevel: Int?,
    val createdAt: String
)
