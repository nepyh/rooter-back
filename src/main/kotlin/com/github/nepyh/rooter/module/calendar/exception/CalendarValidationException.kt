package com.github.nepyh.rooter.module.calendar.exception

import io.ktor.http.HttpStatusCode

sealed class CalendarValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class MissingRangeParamException : CalendarValidationException(
        HttpStatusCode.BadRequest,
        "CALENDAR_001",
        "start, end 파라미터가 필요합니다."
    )
    class InvalidDateFormatException : CalendarValidationException(
        HttpStatusCode.BadRequest,
        "CALENDAR_002",
        "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
    class InvalidDateRangeException : CalendarValidationException(
        HttpStatusCode.BadRequest,
        "CALENDAR_003",
        "start 는 end 보다 늦을 수 없습니다."
    )
    class InvalidTitleException : CalendarValidationException(
        HttpStatusCode.BadRequest,
        "CALENDAR_004",
        "제목은 1~100자여야 합니다."
    )
}
