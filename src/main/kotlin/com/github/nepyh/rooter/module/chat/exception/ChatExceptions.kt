package com.github.nepyh.rooter.module.chat.exception

import io.ktor.http.HttpStatusCode

class DailyPlanNotFoundException : Exception("존재하지 않거나 본인 소유가 아닌 일일 계획입니다.")

class ChatSessionNotFoundException : Exception("존재하지 않거나 본인 소유가 아닌 채팅 세션입니다.")

class ChatTurnNotFoundException : Exception("존재하지 않거나 본인 소유가 아닌 채팅 메시지입니다.")

sealed class ChatValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class MessageRequiredException : ChatValidationException(
        HttpStatusCode.BadRequest, "CHAT_001", "메시지를 입력해주세요."
    )

    class FeedbackNotAllowedException : ChatValidationException(
        HttpStatusCode.BadRequest, "CHAT_FEEDBACK_NOT_ALLOWED", "좋아요·싫어요는 챗봇 답변에만 남길 수 있습니다."
    )
}
