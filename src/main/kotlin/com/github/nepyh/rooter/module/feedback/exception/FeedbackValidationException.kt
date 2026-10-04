package com.github.nepyh.rooter.module.feedback.exception

import io.ktor.http.HttpStatusCode

sealed class FeedbackValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidDifficultyException : FeedbackValidationException(
        HttpStatusCode.BadRequest, "FEEDBACK_001", "difficulty는 쉬움, 적당, 어려움 중 하나여야 합니다."
    )
    class InvalidTimeSpentMinutesException : FeedbackValidationException(
        HttpStatusCode.BadRequest, "FEEDBACK_002", "timeSpentMinutes는 1 이상이어야 합니다."
    )
    class InvalidFocusLevelException : FeedbackValidationException(
        HttpStatusCode.BadRequest, "FEEDBACK_003", "focusLevel은 1~5 사이여야 합니다."
    )
}
