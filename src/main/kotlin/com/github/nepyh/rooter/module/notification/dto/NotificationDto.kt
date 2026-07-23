package com.github.nepyh.rooter.module.notification.dto

import kotlinx.serialization.Serializable

@Serializable
data class DeviceTokenRegisterRequest(
    val token: String,
    val platform: String   // "ANDROID" | "IOS"
)
