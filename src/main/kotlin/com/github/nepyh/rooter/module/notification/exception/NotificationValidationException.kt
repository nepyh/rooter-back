package com.github.nepyh.rooter.module.notification.exception

sealed class NotificationValidationException(message: String) : Exception(message) {
    class InvalidPlatformException : NotificationValidationException("platform 은 ANDROID 또는 IOS 여야 합니다.")
}
