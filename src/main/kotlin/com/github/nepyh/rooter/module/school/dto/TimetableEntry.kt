package com.github.nepyh.rooter.module.school.dto

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 시간표 한 교시 항목.
 * date 는 NICE 원본(YYYYMMDD) 을 파싱한 날짜 — 소비처(캘린더/스케줄링) 에서 바로 비교 가능.
 */
@Serializable
data class TimetableEntry(
    @Serializable(with = NiceDateSerializer::class) val date: LocalDate,
    val period: Int,
    val subject: String,
    val className: String
)
