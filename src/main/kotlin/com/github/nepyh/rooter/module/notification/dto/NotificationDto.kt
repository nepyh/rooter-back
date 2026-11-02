package com.github.nepyh.rooter.module.notification.dto

import com.github.nepyh.rooter.module.notification.model.NotificationType
import kotlinx.serialization.Serializable

@Serializable
data class NotificationResponse(
    val id: Int,
    val type: NotificationType,      // 앱이 눌렀을 때 이동할 화면을 고르는 기준
    val title: String,
    val body: String,
    val data: Map<String, String>,   // 이동 대상 등 부가 정보. 예: TASK_REMINDER → {"planTaskId": "12"}
    val read: Boolean,
    val readAt: String?,             // 읽은 시각 (오프셋 포함). 안 읽었으면 null
    val createdAt: String            // 알림이 생긴 시각 (오프셋 포함)
)

@Serializable
data class NotificationListResponse(
    val notifications: List<NotificationResponse>, // 최신순
    val nextCursor: Int?                           // 다음 페이지에 넘길 cursor. null 이면 마지막 페이지
)

/** 테스트 알림 요청. 둘 다 생략하거나 비우면 기본 문구로 보낸다 (body 는 {} 로 보내면 된다) */
@Serializable
data class NotificationTestRequest(
    val title: String? = null, // 최대 100자, 넘으면 자른다
    val body: String? = null   // 최대 500자, 넘으면 자른다
)

@Serializable
data class NotificationTestResponse(
    val notification: NotificationResponse, // 알림함에 남은 알림
    val pushedDevices: Int                  // Expo 가 받아들인 기기 수. 0 이면 등록된 기기 토큰이 없다 (POST /notifications/device-tokens 먼저)
)

@Serializable
data class UnreadCountResponse(
    val unreadCount: Long
)

@Serializable
data class ReadAllResponse(
    val updatedCount: Int // 이번 요청으로 읽음 처리된 개수
)

@Serializable
data class NotificationSettingsResponse(
    val taskReminderEnabled: Boolean,  // 할일 시작 전 푸시를 받을지. 꺼도 알림함에는 남는다
    val taskReminderLeadMinutes: Int   // 할일 시작 몇 분 전에 보낼지 (0~60)
)

/** 보낸 필드만 바꾼다. 둘 다 생략하면 아무것도 바꾸지 않고 현재 값을 돌려준다. */
@Serializable
data class NotificationSettingsUpdateRequest(
    val taskReminderEnabled: Boolean? = null,
    val taskReminderLeadMinutes: Int? = null
)
