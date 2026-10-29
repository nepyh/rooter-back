package com.github.nepyh.rooter.module.notification.exception

import io.ktor.http.HttpStatusCode

sealed class DeviceTokenValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class InvalidPushTokenException : DeviceTokenValidationException(
        HttpStatusCode.BadRequest,
        "INVALID_PUSH_TOKEN",
        "푸시 토큰 형식이 올바르지 않습니다. (ExponentPushToken[...], 255자 이하)"
    )
}
