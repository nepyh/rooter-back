package com.github.nepyh.rooter.module.taskquiz.exception

sealed class TaskQuizValidationException(message: String) : Exception(message) {
    class AlreadySubmittedException : TaskQuizValidationException("이미 채점된 퀴즈입니다.")
    class InvalidAnswerException : TaskQuizValidationException("퀴즈 문제 구성과 일치하지 않는 답안입니다.")
}
