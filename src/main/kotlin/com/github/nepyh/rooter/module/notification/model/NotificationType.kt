package com.github.nepyh.rooter.module.notification.model

import kotlinx.serialization.Serializable

/**
 * 알림 종류 (notifications.type). DDL 에 check 제약이 없어 서버가 이 enum 으로 검증한다 — 종류가 늘면 여기에 추가한다.
 * 앱은 이 값으로 알림을 눌렀을 때 이동할 화면을 고른다.
 */
@Serializable
enum class NotificationType {
    TASK_REMINDER // 할일 시작 전 알림. data: planTaskId
}
