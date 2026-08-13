package com.github.nepyh.rooter.module.planboard.exception

import io.ktor.http.HttpStatusCode

sealed class PlanBoardValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidTitleException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "BOARD_001",
        "제목은 1~100자여야 합니다."
    )
    class InvalidDateFormatException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "BOARD_002",
        "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
    class InvalidDateRangeException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "BOARD_003",
        "종료일은 시작일보다 빠를 수 없습니다."
    )
}
