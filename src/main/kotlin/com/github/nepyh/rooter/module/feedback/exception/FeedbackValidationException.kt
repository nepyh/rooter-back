package com.github.nepyh.rooter.module.feedback.exception

sealed class FeedbackValidationException(message: String) : Exception(message) {
    class InvalidDifficultyException : FeedbackValidationException("difficulty는 쉬움, 적당, 어려움 중 하나여야 합니다.")
    class InvalidTimeSpentMinutesException : FeedbackValidationException("timeSpentMinutes는 1 이상이어야 합니다.")
    class InvalidFocusLevelException : FeedbackValidationException("focusLevel은 1~5 사이여야 합니다.")
}
