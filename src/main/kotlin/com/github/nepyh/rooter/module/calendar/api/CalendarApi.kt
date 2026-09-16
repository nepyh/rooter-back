package com.github.nepyh.rooter.module.calendar.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.calendar.CalendarService
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventCreateRequest
import com.github.nepyh.rooter.module.calendar.dto.CalendarEventResponse
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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.LocalDate

@OptIn(ExperimentalKtorApi::class)
fun CalendarApi(calendarService: CalendarService) = ApiRoute("calendar") {
    authenticate("auth-jwt") {
        get("") {
            val startParam = call.request.queryParameters["start"]
            val endParam = call.request.queryParameters["end"]
            if (startParam == null || endParam == null) {
                throw CalendarValidationException.MissingRangeParamException()
            }
            val start = runCatching { LocalDate.parse(startParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }
            val end = runCatching { LocalDate.parse(endParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }

            val response = calendarService.getRange(call.userId(), start, end)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("Calendar")
            summary = "기간별 캘린더 조회 (일별 공부 시간 + 시험 D-Day + 개인 일정)"
            description = "start~end 범위의 날짜별 계획 공부 시간(estimatedMinutes 합), 시험 일정(D-Day), 사용자가 등록한 개인 일정을 함께 반환"
            parameters {
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
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "start/end 누락 (code=CALENDAR_001), 날짜 형식 오류 (code=CALENDAR_002), 또는 start 가 end 보다 늦음 (code=CALENDAR_003)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{date}") {
            val dateParam = call.parameters["date"]
                ?: throw CalendarValidationException.InvalidDateFormatException()
            val date = runCatching { LocalDate.parse(dateParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }

            val response = calendarService.getDaySummary(call.userId(), date)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("Calendar")
            summary = "특정 날짜 학습 이행 요약 조회"
            description = "해당 날짜의 태스크 완료 개수/비율을 그때그때 계산해서 반환하고, 그 날짜의 개인 일정도 함께 반환"
            parameters {
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
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "날짜 형식이 올바르지 않음 (code=CALENDAR_002)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("events") {
            val request = call.receive<CalendarEventCreateRequest>()
            val response = calendarService.createEvent(call.userId(), request)
            call.respond(HttpStatusCode.Created, response)
        }.describe {
            tag("Calendar")
            summary = "개인 일정 추가"
            description = "생일, 약속 등 plan_board 와 무관한 개인 일정을 캘린더에 추가"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<CalendarEventCreateRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<CalendarEventResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "제목이 1~100자를 벗어남 (code=CALENDAR_004), 또는 날짜 형식 오류 (code=CALENDAR_002)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        delete("events/{id}") {
            val eventId = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            calendarService.deleteEvent(call.userId(), eventId)
            call.respond(HttpStatusCode.OK, mapOf("message" to "삭제되었습니다."))
        }.describe {
            tag("Calendar")
            summary = "개인 일정 삭제"
            description = "본인 소유의 일정만 삭제 가능"
            parameters {
                path("id") {
                    description = "일정 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "삭제 성공"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일정"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}

private fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
