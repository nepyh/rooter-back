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
    class AlreadyAnsweredException : TaskQuizValidationException(
        HttpStatusCode.BadRequest, "TASK_QUIZ_QUESTION_ALREADY_ANSWERED", "이미 답변한 문제는 다시 답할 수 없습니다."
    )
    class IncompleteAnswersException : TaskQuizValidationException(
        HttpStatusCode.BadRequest, "TASK_QUIZ_INCOMPLETE_ANSWERS", "아직 답하지 않은 문제가 있습니다."
    )
    class GenerationFailedException : TaskQuizValidationException(
        HttpStatusCode.BadGateway, "TASK_QUIZ_GENERATION_FAILED", "퀴즈를 만들지 못했습니다. 잠시 후 다시 시도해주세요."
    )
}
