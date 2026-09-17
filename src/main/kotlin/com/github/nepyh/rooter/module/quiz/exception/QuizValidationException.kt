package com.github.nepyh.rooter.module.quiz.exception

sealed class QuizValidationException(message: String) : Exception(message) {
    class InvalidDateFormatException : QuizValidationException("날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)")
    class NoPlanForDateException : QuizValidationException("해당 날짜에 계획이 없습니다.")
    class AlreadySubmittedException : QuizValidationException("이미 제출한 퀴즈입니다.")
    class QuizGenerationFailedException : QuizValidationException("퀴즈 생성에 실패했습니다.")
    class InvalidAnswerException : QuizValidationException("퀴즈 문제 구성과 일치하지 않는 답안입니다.")
}
