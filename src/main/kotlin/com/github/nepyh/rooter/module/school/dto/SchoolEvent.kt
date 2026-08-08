package com.github.nepyh.rooter.module.school.dto

import kotlinx.serialization.Serializable

/**
 * 학사일정 이벤트 (방학, 행사, 일부 학교는 시험기간 포함).
 * date 는 NICE 원본 형식(YYYYMMDD) 그대로 사용한다.
 */
@Serializable
data class SchoolEvent(
    val date: String,
    val name: String
)
