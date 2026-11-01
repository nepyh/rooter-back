package com.github.nepyh.rooter.module.notification.dto

import com.github.nepyh.rooter.module.notification.model.DevicePlatform
import kotlinx.serialization.Serializable

@Serializable
data class DeviceTokenRegisterRequest(
    val token: String,               // "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]"
    val platform: DevicePlatform
)

@Serializable
data class DeviceTokenResponse(
    val token: String,
    val platform: DevicePlatform
)
