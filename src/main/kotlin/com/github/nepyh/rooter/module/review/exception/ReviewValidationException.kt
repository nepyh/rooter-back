package com.github.nepyh.rooter.module.review.exception

import io.ktor.http.HttpStatusCode

sealed class ReviewValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidPageException : ReviewValidationException(
        HttpStatusCode.BadRequest, "REVIEW_INVALID_PAGE", "cursor 는 정수, limit 은 1~50 이어야 합니다."
    )
    class AlreadyClearedException : ReviewValidationException(
        HttpStatusCode.BadRequest, "REVIEW_ALREADY_CLEARED", "이미 맞혀서 복습 목록에서 빠진 문제입니다."
    )
    class InvalidAnswerException : ReviewValidationException(
        HttpStatusCode.BadRequest, "REVIEW_INVALID_ANSWER", "이 문제의 보기가 아닙니다."
    )
}
