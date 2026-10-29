package com.github.nepyh.rooter.module.notification.exception

import com.github.nepyh.rooter.module.notification.model.MAX_TASK_REMINDER_LEAD_MINUTES
import io.ktor.http.HttpStatusCode

sealed class NotificationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    /** 남의 알림도 같은 404 — 다른 사용자의 알림 id 가 있는지 알려주지 않는다. */
    class NotFoundException : NotificationException(
        HttpStatusCode.NotFound, "NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다."
    )
    class InvalidPageException : NotificationException(
        HttpStatusCode.BadRequest, "NOTIFICATION_INVALID_PAGE", "cursor 값이 정수가 아니거나 limit 이 1~50 밖입니다."
    )
    class InvalidLeadMinutesException : NotificationException(
        HttpStatusCode.BadRequest, "INVALID_LEAD_MINUTES", "알림 시각은 할일 시작 0~${MAX_TASK_REMINDER_LEAD_MINUTES}분 전이어야 합니다."
    )
}
