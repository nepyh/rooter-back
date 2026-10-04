package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.planboard.BusyTimeService
import com.github.nepyh.rooter.module.planboard.dto.BusyTimeResponse
import com.github.nepyh.rooter.module.planboard.exception.BusyTimeValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.LocalDate

@OptIn(ExperimentalKtorApi::class)
fun BusyTimeApi(busyTimeService: BusyTimeService) = ApiRoute("busy-times") {

    authenticate("auth-jwt") {
        get("") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            fun parse(name: String): LocalDate? = call.request.queryParameters[name]?.let {
                runCatching { LocalDate.parse(it) }.getOrElse { throw BusyTimeValidationException.InvalidDateParamException() }
            }
            val startDate = parse("startDate") ?: todayInAppZone()
            val endDate = parse("endDate") ?: startDate

            call.respond(HttpStatusCode.OK, busyTimeService.getBusyTimes(userId, startDate, endDate))
        }.describe {
            tag("BusyTime")
            summary = "날짜별 바쁜 시간 / 빈 시간 조회"
            description = "로그인한 사용자의 날짜별 막힌 시간과 빈 시간을 돌려줌. 할일을 직접 추가하거나 옮길 때 겹치지 않는 시간을 보여주는 용도. " +
                "busyTimes.type: SLEEP(취침 00:00~06:30, 23:00~24:00) / SCHOOL(등교일 00:00~하교, NICE 시간표 기준·없으면 16:30) / " +
                "UNAVAILABLE(사용자가 등록한 요일별 불가능 시간) / TASK(모든 플랜보드의 기존 할일, taskId·planBoardId 포함). " +
                "freeTimes 는 busyTimes 를 전부 뺀 시간. 시각은 HH:mm, 하루 끝은 24:00. " +
                "AI 계획 생성과 같은 기준으로 계산함 (공휴일·방학은 isSchoolDay=false)"
            parameters {
                query("startDate") {
                    description = "조회 시작일 (yyyy-MM-dd). 생략하면 오늘(한국 시간)"
                    required = false
                    schema = jsonSchema<String>()
                }
                query("endDate") {
                    description = "조회 종료일 (yyyy-MM-dd, 포함). 생략하면 startDate 하루만. 최대 31일"
                    required = false
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<BusyTimeResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "날짜 형식 오류 (code=INVALID_DATE_PARAM), endDate 가 startDate 보다 빠르거나 31일 초과 (code=INVALID_DATE_RANGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
