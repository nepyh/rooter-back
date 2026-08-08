package com.github.nepyh.rooter.module.school.dto

import kotlinx.serialization.Serializable

/**
 * 시간표 한 교시 항목.
 * date 는 NICE 원본 형식(YYYYMMDD) 그대로 사용한다.
 */
@Serializable
data class TimetableEntry(
    val date: String,
    val period: Int,
    val subject: String,
    val className: String
)
