package com.github.nepyh.rooter.module.planboard.exception

import io.ktor.http.HttpStatusCode

sealed class PlanBoardValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidTitleException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_TITLE",
        "제목은 1~100자여야 합니다."
    )
    class InvalidDateFormatException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_DATE_FORMAT",
        "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
    class InvalidDateRangeException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_DATE_RANGE",
        "종료일은 시작일보다 빠를 수 없습니다."
    )
    class SubjectsRequiredException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "SUBJECTS_REQUIRED",
        "과목 범위를 1개 이상 입력해주세요."
    )
    class InvalidSubjectRangeException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_SUBJECT_RANGE",
        "존재하지 않는 교과서/단원이거나, 시작 단원이 끝 단원보다 뒤에 있습니다."
    )
    class MissingDateInfoException : PlanBoardValidationException(
        HttpStatusCode.BadRequest,
        "MISSING_DATE_INFO",
        "examDate 또는 daysRemaining 중 하나는 반드시 입력해야 합니다."
    )
    class GenerationFailedException : PlanBoardValidationException(
        HttpStatusCode.BadGateway,
        "GENERATION_FAILED",
        "AI 계획 생성에 실패했습니다. 잠시 후 다시 시도해주세요."
    )
}
