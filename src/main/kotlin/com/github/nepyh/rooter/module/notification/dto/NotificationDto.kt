package com.github.nepyh.rooter.module.notification.dto

import kotlinx.serialization.Serializable

@Serializable
data class DeviceTokenRegisterRequest(
    val token: String,
    val platform: String   // "ANDROID" | "IOS"
)

@Serializable
data class NotificationSettingsResponse(
    val taskReminderEnabled: Boolean
)

@Serializable
data class NotificationSettingsUpdateRequest(
    val taskReminderEnabled: Boolean
)
