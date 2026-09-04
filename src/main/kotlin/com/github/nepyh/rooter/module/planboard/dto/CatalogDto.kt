package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class SubjectResponse(
    val id: Int,
    val name: String
)

@Serializable
data class TextbookResponse(
    val id: Int,
    val subjectId: Int,
    val publisherId: Int?,
    val title: String,
    val aiStatus: String
)

@Serializable
data class ChapterResponse(
    val id: Int,
    val textbookId: Int,
    val parentId: Int?,
    val chapterName: String,
    val chapterOrder: Int
)

@Serializable
data class ChapterTreeResponse(
    val id: Int,
    val chapterName: String,
    val chapterOrder: Int,
    val children: List<ChapterTreeResponse>
)

@Serializable
data class TextbookDetailResponse(
    val id: Int,
    val subjectId: Int,
    val subjectName: String,
    val publisherId: Int?,
    val title: String,
    val aiStatus: String,
    val chapters: List<ChapterTreeResponse>
)