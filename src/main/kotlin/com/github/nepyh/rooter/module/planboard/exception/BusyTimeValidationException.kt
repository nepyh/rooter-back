package com.github.nepyh.rooter.module.planboard.exception

import io.ktor.http.HttpStatusCode

sealed class BusyTimeValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidDateParamException : BusyTimeValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_DATE_PARAM",
        "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
    class InvalidDateRangeException : BusyTimeValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_DATE_RANGE",
        "endDate 는 startDate 와 같거나 늦어야 하고, 한 번에 최대 31일까지 조회할 수 있습니다."
    )
}
