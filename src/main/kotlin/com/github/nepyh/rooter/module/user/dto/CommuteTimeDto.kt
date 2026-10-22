package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class CommuteTimeRequest(
    val commuteMinutes: Int // 하교 후 집에 도착하기까지 걸리는 시간(분), 0~180
)

@Serializable
data class CommuteTimeResponse(
    val commuteMinutes: Int,
    val isDefault: Boolean // true 면 아직 저장 안 한 사용자 — 기본값 40분을 쓰는 중
)
