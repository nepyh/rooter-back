package com.github.nepyh.rooter.module.calendar.exception

sealed class CalendarValidationException(message: String) : Exception(message) {
    class MissingRangeParamException : CalendarValidationException("start, end 파라미터가 필요합니다.")
    class InvalidDateFormatException : CalendarValidationException("날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)")
    class InvalidDateRangeException : CalendarValidationException("start 는 end 보다 늦을 수 없습니다.")
    class InvalidTitleException : CalendarValidationException("제목은 1~100자여야 합니다.")
}
