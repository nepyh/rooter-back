package com.github.nepyh.rooter.module.leveltest.exception

sealed class LevelTestValidationException(message: String) : Exception(message) {
    class InvalidGradeException : LevelTestValidationException("grade는 1~3(중학교 학년) 사이여야 합니다.")
    class AlreadySubmittedException : LevelTestValidationException("이미 제출한 실력 테스트입니다.")
    class InvalidAnswerException : LevelTestValidationException("테스트 문제 구성과 일치하지 않는 답안입니다.")
    class TestGenerationFailedException : LevelTestValidationException("실력 테스트 생성에 실패했습니다.")
}
