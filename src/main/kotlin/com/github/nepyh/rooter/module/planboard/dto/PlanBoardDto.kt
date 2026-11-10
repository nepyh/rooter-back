package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanBoardCreateRequest(
    val title: String,
    val startDate: String,
    val endDate: String,
    val examDate: String? = null,
    val description: String? = null // 목록에서 보드를 구분하는 설명. 500자 이하, 빈 문자열은 설명 없음으로 본다
)

@Serializable
data class PlanBoardResponse(
    val id: Int,
    val title: String,
    val description: String?, // 설명 없으면 null
    val startDate: String,
    val endDate: String,
    val examDate: String?,
    val isArchived: Boolean, // 보관한 보드인지. 목록 조회는 기본으로 보관된 보드를 뺀다
    val createdAt: String,
    val updatedAt: String // 마지막 수정 시각 (제목·설명·기간·보관 변경 시 갱신)
)

@Serializable
data class PlanBoardCreateResponse(
    val id: Int,
    val message: String
)

@Serializable
data class PlanBoardUpdateRequest(
    val title: String? = null,
    // null = 변경 없음, 빈 문자열 = 설명 지우기 (다른 필드와 같은 "전달한 필드만 수정" 규칙)
    val description: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val examDate: String? = null,
    val isArchived: Boolean? = null // true = 보관, false = 보관 해제
)
