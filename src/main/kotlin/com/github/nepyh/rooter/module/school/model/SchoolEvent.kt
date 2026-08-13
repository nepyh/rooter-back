package com.github.nepyh.rooter.module.school.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 학사일정 이벤트 (방학, 행사, 일부 학교는 시험기간 포함).
 * date 는 NICE 원본(YYYYMMDD) 을 파싱한 날짜 — 소비처(캘린더) 에서 바로 비교 가능.
 */
@Serializable
data class SchoolEvent(
    @Serializable(with = NiceDateSerializer::class) val date: LocalDate,
    val name: String
)
