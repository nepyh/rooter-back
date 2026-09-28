package com.github.nepyh.rooter.module.taskquiz.exception

import io.ktor.http.HttpStatusCode

sealed class TaskQuizValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class AlreadySubmittedException : TaskQuizValidationException(
        HttpStatusCode.BadRequest, "TASK_QUIZ_ALREADY_SUBMITTED", "이미 채점된 퀴즈입니다."
    )
    class InvalidAnswerException : TaskQuizValidationException(
        HttpStatusCode.BadRequest, "TASK_QUIZ_INVALID_ANSWER", "퀴즈 문제 구성과 일치하지 않는 답안입니다."
    )
}
