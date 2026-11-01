package com.github.nepyh.rooter.module.notification.model

import kotlinx.serialization.Serializable

/** 푸시 토큰을 등록한 기기의 OS (user_device_tokens.platform, rooter-ddl chk_user_device_tokens_platform). */
@Serializable
enum class DevicePlatform {
    IOS, ANDROID
}
