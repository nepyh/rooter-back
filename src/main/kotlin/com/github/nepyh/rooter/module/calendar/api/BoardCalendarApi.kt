package com.github.nepyh.rooter.module.calendar.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.calendar.CalendarService
import com.github.nepyh.rooter.module.calendar.dto.CalendarRangeResponse
import com.github.nepyh.rooter.module.calendar.dto.DailyCompletionResponse
import com.github.nepyh.rooter.module.calendar.exception.CalendarValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.LocalDate

/**
 * 보드 하나 기준 캘린더. 한 유저가 여러 플랜보드를 가질 때 클라이언트가 고른 보드의 계획만 보여주기 위한 경로라
 * 보드 id 를 쿼리 파라미터가 아니라 path 로 받는다. 개인 일정은 사용자 단위라 보드와 무관하게 항상 포함된다.
 */
@OptIn(ExperimentalKtorApi::class)
fun BoardCalendarApi(calendarService: CalendarService) = ApiRoute("plan-boards") {

    authenticate("auth-jwt") {
        get("/{boardId}/calendar") {
            val boardId = call.boardId()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_BOARD_ID", "잘못된 boardId 입니다."))

            val startParam = call.request.queryParameters["start"]
            val endParam = call.request.queryParameters["end"]
            if (startParam == null || endParam == null) {
                throw CalendarValidationException.MissingRangeParamException()
            }
            val start = runCatching { LocalDate.parse(startParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }
            val end = runCatching { LocalDate.parse(endParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }

            call.respond(HttpStatusCode.OK, calendarService.getRange(call.userId(), start, end, boardId))
        }.describe {
            tag("Calendar")
            summary = "플랜보드 기간별 캘린더 조회 (그 보드의 공부 시간 + 시험 D-Day + 개인 일정)"
            description = "start~end 범위에서 이 보드의 날짜별 예상 공부 시간 합과 이 보드의 시험 일정만 돌려준다. " +
                "개인 일정은 사용자 단위라 보드와 무관하게 함께 온다. 본인 보드만 가능"
            parameters {
                path("boardId") {
                    description = "플랜보드 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
                query("start") {
                    description = "조회 시작일 (yyyy-MM-dd)"
                    required = true
                    schema = jsonSchema<String>()
                }
                query("end") {
                    description = "조회 종료일 (yyyy-MM-dd)"
                    required = true
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<CalendarRangeResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 boardId, start/end 누락 (code=CALENDAR_001), 날짜 형식 오류 (code=CALENDAR_002), 또는 start 가 end 보다 늦음 (code=CALENDAR_003)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 플랜보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 플랜보드"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("/{boardId}/calendar/{date}") {
            val boardId = call.boardId()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_BOARD_ID", "잘못된 boardId 입니다."))

            val dateParam = call.parameters["date"]
                ?: throw CalendarValidationException.InvalidDateFormatException()
            val date = runCatching { LocalDate.parse(dateParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }

            call.respond(HttpStatusCode.OK, calendarService.getDaySummary(call.userId(), date, boardId))
        }.describe {
            tag("Calendar")
            summary = "플랜보드 특정 날짜 학습 이행 요약 조회"
            description = "이 보드의 그 날짜 할일로 완료 개수/비율을 계산해 반환한다 (다른 보드 할일은 빠진다). 개인 일정은 함께 온다. 본인 보드만 가능"
            parameters {
                path("boardId") {
                    description = "플랜보드 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
                path("date") {
                    description = "조회할 날짜 (yyyy-MM-dd)"
                    required = true
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<DailyCompletionResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 boardId, 또는 날짜 형식이 올바르지 않음 (code=CALENDAR_002)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 플랜보드가 아님"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 플랜보드"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}

private fun ApplicationCall.boardId(): Int? = parameters["boardId"]?.toIntOrNull()

private fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
