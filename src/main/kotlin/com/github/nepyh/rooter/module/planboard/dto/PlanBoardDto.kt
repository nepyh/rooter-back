package com.github.nepyh.rooter.module.planboard.dto

import kotlinx.serialization.Serializable

@Serializable
data class PlanBoardCreateRequest(
    val title: String,
    val startDate: String,
    val endDate: String,
    val examDate: String? = null,
    val examEndDate: String? = null, // 시험 마지막 날 "yyyy-MM-dd". examDate 가 있어야 하고 그보다 앞설 수 없다 (#376)
    val description: String? = null // 목록에서 보드를 구분하는 설명. 500자 이하, 빈 문자열은 설명 없음으로 본다
)

@Serializable
data class PlanBoardResponse(
    val id: Int,
    val title: String,
    val description: String?, // 설명 없으면 null
    val startDate: String,
    val endDate: String,
    val examDate: String?, // 시험 첫날
    val examEndDate: String?, // 시험 마지막 날. null 이면 examDate 하루짜리 시험
    val isArchived: Boolean, // 보관한 보드인지. 목록 조회는 기본으로 보관된 보드를 뺀다
    val isRepresentative: Boolean, // 대표 플랜인지 (유저당 1개). 홈 화면이 이 보드의 디데이를 보여준다
    val createdAt: String,
    val updatedAt: String, // 마지막 수정 시각 (제목·설명·기간·보관 변경 시 갱신)
    // AI 계획 생성 상태 (#405): COMPLETED / GENERATING(뒷부분을 만드는 중) / FAILED(재시도까지 실패 — 다시 만들기 필요)
    val generationStatus: String = "COMPLETED",
    val generatedUntil: String? = null // 만들어진 마지막 날짜. GENERATING·FAILED 일 때만, 그 뒤 날짜는 아직 계획이 없다
)

@Serializable
data class PlanSubjectReplaceRequest(
    // 이 보드의 과목 범위를 통째로 교체한다. 빈 배열이면 전부 지운다 (수정 화면의 "교과서 저장")
    val subjects: List<PlanSubjectCreateRequest> = emptyList()
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
    val examEndDate: String? = null, // 시험 마지막 날. 바꾼 뒤의 examDate 보다 앞설 수 없다
    val isArchived: Boolean? = null // true = 보관, false = 보관 해제
)
