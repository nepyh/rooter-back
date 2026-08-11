package com.github.nepyh.rooter.module.school.exception

import io.ktor.http.HttpStatusCode

/**
 * NICE API 호출 실패를 나타내는 예외.
 * NICE RESULT 코드(INFO-xxx) 를 HTTP 의미에 맞게 매핑한다.
 */
sealed class NiceApiException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {

    /** INFO-100: 인증키가 유효하지 않음 (누락/만료/오타) */
    class InvalidKeyException(message: String? = null) : NiceApiException(
        HttpStatusCode.Unauthorized,
        "NICE_INVALID_KEY",
        message ?: "NICE 인증키가 유효하지 않습니다."
    )

    /** INFO-300: 일일 호출량 초과 등 요청 제한 */
    class RateLimitedException(message: String? = null) : NiceApiException(
        HttpStatusCode.TooManyRequests,
        "NICE_RATE_LIMITED",
        message ?: "NICE API 호출 한도를 초과했습니다."
    )

    /** INFO-400: 파라미터 오류 (잘못된 schoolId 등) */
    class BadRequestException(message: String? = null) : NiceApiException(
        HttpStatusCode.BadRequest,
        "NICE_BAD_REQUEST",
        message ?: "NICE API 요청 파라미터가 올바르지 않습니다."
    )

    /** INFO-500: NICE 서버 오류 */
    class ServerException(message: String? = null) : NiceApiException(
        HttpStatusCode.BadGateway,
        "NICE_SERVER_ERROR",
        message ?: "NICE 서버 오류가 발생했습니다."
    )

    /** 예상치 못한 응답 (RESULT 블록 없음 등) */
    class UnexpectedResponseException(message: String? = null) : NiceApiException(
        HttpStatusCode.BadGateway,
        "NICE_UNEXPECTED_RESPONSE",
        message ?: "NICE API 응답을 해석할 수 없습니다."
    )
}
