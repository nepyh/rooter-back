package com.github.nepyh.rooter.module.planboard.exception

import io.ktor.http.HttpStatusCode

sealed class PlanTaskValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidTaskNameException : PlanTaskValidationException(
        HttpStatusCode.BadRequest,
        "TASK_001",
        "태스크 이름은 1~150자여야 합니다."
    )
    class InvalidPlanDateException : PlanTaskValidationException(
        HttpStatusCode.BadRequest,
        "TASK_002",
        "계획 날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
    class InvalidTimeFormatException : PlanTaskValidationException(
        HttpStatusCode.BadRequest,
        "TASK_003",
        "시간 형식이 올바르지 않습니다. (HH:mm)"
    )
    class InvalidEstimatedMinutesException : PlanTaskValidationException(
        HttpStatusCode.BadRequest,
        "TASK_004",
        "예상 소요 시간은 1분 이상이어야 합니다."
    )
    class PlanDateOutOfRangeException : PlanTaskValidationException(
        HttpStatusCode.BadRequest,
        "TASK_005",
        "계획 날짜가 플랜보드 기간을 벗어났습니다."
    )
    class InvalidDateParamException : PlanTaskValidationException(
        HttpStatusCode.BadRequest,
        "TASK_006",
        "date 파라미터 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
}
