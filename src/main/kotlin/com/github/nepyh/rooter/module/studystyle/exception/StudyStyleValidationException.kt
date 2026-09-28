package com.github.nepyh.rooter.module.studystyle.exception

import io.ktor.http.HttpStatusCode

sealed class StudyStyleValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class EmptyAnswersException : StudyStyleValidationException(
        HttpStatusCode.BadRequest, "STUDY_STYLE_EMPTY_ANSWERS", "answers는 1개 이상이어야 합니다."
    )
    class InvalidQuestionNumberException : StudyStyleValidationException(
        HttpStatusCode.BadRequest, "STUDY_STYLE_INVALID_QUESTION_NUMBER", "questionNumber는 1~7 사이여야 합니다."
    )
    class InvalidAnswerOptionException : StudyStyleValidationException(
        HttpStatusCode.BadRequest, "STUDY_STYLE_INVALID_ANSWER_OPTION", "answerOption은 1~4 사이여야 합니다. (4 = 모르겠어요)"
    )
    class DuplicateQuestionNumberException : StudyStyleValidationException(
        HttpStatusCode.BadRequest, "STUDY_STYLE_DUPLICATE_QUESTION_NUMBER", "같은 questionNumber에 대한 답변이 중복 제출되었습니다."
    )
}
