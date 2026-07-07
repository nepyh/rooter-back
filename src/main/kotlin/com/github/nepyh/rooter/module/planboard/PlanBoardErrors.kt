package com.github.nepyh.rooter.module.planboard

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*

// TODO: StatusPages 도입 시 common으로 이동 (이슈 #XX)
enum class ErrorCode(val status: HttpStatusCode, val defaultMessage: String) {
    COMMON_001(HttpStatusCode.BadRequest, "요청 본문 형식이 올바르지 않습니다."),
    COMMON_002(HttpStatusCode.InternalServerError, "서버 내부 오류가 발생했습니다."),

    BOARD_001(HttpStatusCode.BadRequest, "제목은 1~100자여야 합니다."),
    BOARD_002(HttpStatusCode.BadRequest, "날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"),
    BOARD_003(HttpStatusCode.BadRequest, "종료일은 시작일보다 빠를 수 없습니다."),
    BOARD_004(HttpStatusCode.NotFound, "존재하지 않는 플랜보드입니다."),

    TASK_001(HttpStatusCode.BadRequest, "태스크 이름은 1~150자여야 합니다."),
    TASK_002(HttpStatusCode.BadRequest, "계획 날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)"),
    TASK_003(HttpStatusCode.BadRequest, "시간 형식이 올바르지 않습니다. (HH:mm)"),
    TASK_004(HttpStatusCode.BadRequest, "예상 소요 시간은 1분 이상이어야 합니다."),
    TASK_005(HttpStatusCode.BadRequest, "계획 날짜가 플랜보드 기간을 벗어났습니다."),
    TASK_006(HttpStatusCode.BadRequest, "date 파라미터 형식이 올바르지 않습니다. (yyyy-MM-dd)"),
}

class ApiException(
    val errorCode: ErrorCode,
    message: String? = null,
) : RuntimeException(message ?: errorCode.defaultMessage)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)

// TODO: StatusPages 도입 시 이 함수 삭제하고 라우트에서 바로 throw
suspend inline fun ApplicationCall.respondCatching(block: () -> Unit) {
    try {
        block()
    } catch (e: ApiException) {
        respond(e.errorCode.status, ErrorResponse(e.errorCode.name, e.message!!))
    } catch (e: BadRequestException) {
        respond(ErrorCode.COMMON_001.status, ErrorResponse(ErrorCode.COMMON_001.name, ErrorCode.COMMON_001.defaultMessage))
    } catch (e: Throwable) {
        application.log.error("Unhandled exception", e)
        respond(ErrorCode.COMMON_002.status, ErrorResponse(ErrorCode.COMMON_002.name, ErrorCode.COMMON_002.defaultMessage))
    }
}