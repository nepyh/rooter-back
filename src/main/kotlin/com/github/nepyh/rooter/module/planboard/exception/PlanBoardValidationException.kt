package com.github.nepyh.rooter.module.planboard.exception

sealed class PlanBoardValidationException(message: String) : Exception(message) {
    class InvalidTitleException : PlanBoardValidationException("제목은 1~100자여야 합니다.")
    class InvalidDateFormatException : PlanBoardValidationException("날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)")
    class InvalidDateRangeException : PlanBoardValidationException("종료일은 시작일보다 빠를 수 없습니다.")
}
