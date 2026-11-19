package com.github.nepyh.rooter.module.notification.model

import kotlinx.serialization.Serializable

/**
 * 알림 종류 (notifications.type). DDL 에 check 제약이 없어 서버가 이 enum 으로 검증한다 — 종류가 늘면 여기에 추가한다.
 * 앱은 이 값으로 알림을 눌렀을 때 이동할 화면을 고른다.
 */
@Serializable
enum class NotificationType {
    TASK_REMINDER, // 할일 시작 전 알림. data: planTaskId
    DAILY_FEEDBACK_REMINDER, // 저녁 19~22시 30분마다 오늘 공부 설문 알림 (#374). data: dailyPlanId — 앱은 그날 설문 화면을 연다
    TEST // 테스트 알림 (POST /notifications/test). data 없음 — 앱은 알림함만 열면 된다
}
