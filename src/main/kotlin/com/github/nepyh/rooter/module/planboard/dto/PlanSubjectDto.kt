package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanSubjectCreateRequest(
    val textbookId: Int,
    val startChapterId: Int,
    val endChapterId: Int,
    val customRangeText: String? = null
)

@Serializable
data class PlanSubjectResponse(
    val id: Int,
    val planBoardId: Int,
    val subjectId: Int,
    val subjectName: String,
    val textbookId: Int,
    val textbookTitle: String,
    val startChapterId: Int,
    val endChapterId: Int,
    val customRangeText: String?
)
