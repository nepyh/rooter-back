package com.github.nepyh.rooter.module.chat.exception

import io.ktor.http.HttpStatusCode

/** 챗봇에 보낼 수 있는 메시지 최대 글자 수 (앞뒤 공백 제외). 프론트 입력창 maxLength 와 같다 (#396) */
const val CHAT_MESSAGE_MAX_LENGTH = 500

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

    class MessageTooLongException : ChatValidationException(
        HttpStatusCode.BadRequest, "CHAT_MESSAGE_TOO_LONG", "메시지는 ${CHAT_MESSAGE_MAX_LENGTH}자까지 보낼 수 있어요."
    )

    class FeedbackNotAllowedException : ChatValidationException(
        HttpStatusCode.BadRequest, "CHAT_FEEDBACK_NOT_ALLOWED", "좋아요·싫어요는 챗봇 답변에만 남길 수 있습니다."
    )
}
