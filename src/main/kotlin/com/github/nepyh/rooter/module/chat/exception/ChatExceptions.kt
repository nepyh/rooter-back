package com.github.nepyh.rooter.module.chat.exception

import io.ktor.http.HttpStatusCode

class DailyPlanNotFoundException : Exception("존재하지 않거나 본인 소유가 아닌 일일 계획입니다.")

sealed class ChatValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class MessageRequiredException : ChatValidationException(
        HttpStatusCode.BadRequest, "CHAT_001", "메시지를 입력해주세요."
    )
}
