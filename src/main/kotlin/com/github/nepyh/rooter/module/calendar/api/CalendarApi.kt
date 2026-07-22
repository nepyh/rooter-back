package com.github.nepyh.rooter.module.calendar.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.calendar.CalendarService
import com.github.nepyh.rooter.module.calendar.dto.CalendarRangeResponse
import com.github.nepyh.rooter.module.calendar.dto.DailyCompletionResponse
import com.github.nepyh.rooter.module.calendar.exception.CalendarValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.LocalDate

@OptIn(ExperimentalKtorApi::class)
fun CalendarApi(calendarService: CalendarService) = ApiRoute("calendar") {
    get("") {
        try {
            val startParam = call.request.queryParameters["start"]
            val endParam = call.request.queryParameters["end"]
            if (startParam == null || endParam == null) {
                throw CalendarValidationException.MissingRangeParamException()
            }
            val start = runCatching { LocalDate.parse(startParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }
            val end = runCatching { LocalDate.parse(endParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }

            val userId = 1 // 💡 로그인 연동 전 임시 유저
            val response = calendarService.getRange(userId, start, end)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: CalendarValidationException.MissingRangeParamException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "CALENDAR_001", "message" to e.message))
        } catch (e: CalendarValidationException.InvalidDateFormatException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "CALENDAR_002", "message" to e.message))
        } catch (e: CalendarValidationException.InvalidDateRangeException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "CALENDAR_003", "message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Calendar")
        summary = "기간별 캘린더 조회 (일별 공부 시간 + 시험 D-Day)"
        description = "start~end 범위의 날짜별 계획 공부 시간(estimatedMinutes 합)과, 그 범위 안에 있는 시험 일정(D-Day)을 함께 반환"
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
            HttpStatusCode.BadRequest {
                description = "start/end 누락 (code=CALENDAR_001), 날짜 형식 오류 (code=CALENDAR_002), 또는 start 가 end 보다 늦음 (code=CALENDAR_003)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }

    get("{date}") {
        try {
            val dateParam = call.parameters["date"]
                ?: throw CalendarValidationException.InvalidDateFormatException()
            val date = runCatching { LocalDate.parse(dateParam) }
                .getOrElse { throw CalendarValidationException.InvalidDateFormatException() }

            val userId = 1 // 💡 로그인 연동 전 임시 유저
            val response = calendarService.getDaySummary(userId, date)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: CalendarValidationException.InvalidDateFormatException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "CALENDAR_002", "message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }.describe {
        tag("Calendar")
        summary = "특정 날짜 학습 이행 요약 조회"
        description = "해당 날짜의 태스크 완료 개수/비율을 그때그때 계산해서 반환"
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
            HttpStatusCode.BadRequest {
                description = "날짜 형식이 올바르지 않음 (code=CALENDAR_002)"
            }
            HttpStatusCode.InternalServerError {
                description = "서버 오류"
            }
        }
    }
}
