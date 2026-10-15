package com.github.nepyh.rooter.module.taskquiz.exception

import io.ktor.http.HttpStatusCode

/**
 * 퀴즈 히스토리 조회 파라미터 검증 실패.
 * code 는 QUIZ_HISTORY_ 접두사를 쓴다 — 일일 퀴즈도 같은 목록에 합류할 예정이라 태스크 퀴즈 흐름의 TASK_QUIZ_ 코드와 분리한다.
 */
sealed class QuizHistoryValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidStatusException : QuizHistoryValidationException(
        HttpStatusCode.BadRequest, "QUIZ_HISTORY_INVALID_STATUS", "status 는 todo 또는 done 이어야 합니다."
    )
    class InvalidPageException : QuizHistoryValidationException(
        HttpStatusCode.BadRequest, "QUIZ_HISTORY_INVALID_PAGE", "cursor 값이 정수가 아니거나 limit 이 1~50 밖입니다."
    )
}
