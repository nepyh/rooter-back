package com.github.nepyh.rooter.module.quiz.exception

import io.ktor.http.HttpStatusCode

sealed class QuizValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidDateFormatException : QuizValidationException(
        HttpStatusCode.BadRequest, "QUIZ_INVALID_DATE", "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"
    )
    class NoPlanForDateException : QuizValidationException(
        HttpStatusCode.BadRequest, "QUIZ_NO_PLAN_FOR_DATE", "해당 날짜에 계획이 없습니다."
    )
    class AlreadySubmittedException : QuizValidationException(
        HttpStatusCode.BadRequest, "QUIZ_ALREADY_SUBMITTED", "이미 제출한 퀴즈입니다."
    )
    class QuizGenerationFailedException : QuizValidationException(
        HttpStatusCode.BadRequest, "QUIZ_GENERATION_FAILED", "퀴즈 생성에 실패했습니다."
    )
    class InvalidAnswerException : QuizValidationException(
        HttpStatusCode.BadRequest, "QUIZ_INVALID_ANSWER", "퀴즈 문제 구성과 일치하지 않는 답안입니다."
    )
}
