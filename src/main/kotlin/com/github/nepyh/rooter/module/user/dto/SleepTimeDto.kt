package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class SleepTimeRequest(
    val sleepTime: String, // 취침 "HH:mm", 예: "23:30" · 자정 넘어 자면 "01:00"
    val wakeTime: String   // 기상 "HH:mm", 예: "07:00"
)

@Serializable
data class SleepTimeResponse(
    val sleepTime: String,
    val wakeTime: String,
    val isDefault: Boolean // true 면 아직 저장 안 한 사용자 — 기본값 23:00 / 06:30 을 쓰는 중
)
