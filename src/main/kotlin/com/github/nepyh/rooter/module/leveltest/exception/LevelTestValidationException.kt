package com.github.nepyh.rooter.module.leveltest.exception

import io.ktor.http.HttpStatusCode

sealed class LevelTestValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidGradeException : LevelTestValidationException(
        HttpStatusCode.BadRequest, "LEVEL_TEST_INVALID_GRADE", "grade는 1~3(중학교 학년) 사이여야 합니다."
    )
    class AlreadySubmittedException : LevelTestValidationException(
        HttpStatusCode.BadRequest, "LEVEL_TEST_ALREADY_SUBMITTED", "이미 제출한 실력 테스트입니다."
    )
    class InvalidAnswerException : LevelTestValidationException(
        HttpStatusCode.BadRequest, "LEVEL_TEST_INVALID_ANSWER", "테스트 문제 구성과 일치하지 않는 답안입니다."
    )
    class TestGenerationFailedException : LevelTestValidationException(
        HttpStatusCode.BadRequest, "LEVEL_TEST_GENERATION_FAILED", "실력 테스트 생성에 실패했습니다."
    )
}
