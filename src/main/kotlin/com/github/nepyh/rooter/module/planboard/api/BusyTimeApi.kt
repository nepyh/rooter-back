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
                "busyTimes.type: SLEEP(취침, 기본 00:00~06:30·23:00~24:00) / SCHOOL_PREP(등교일 등교 전, 자는 시간을 뺀 00:00~08:30 — 보통 기상~08:30) / " +
                "SCHOOL(등교일 08:30~하교, NICE 시간표 기준·없으면 16:30) / " +
                "COMMUTE(등교일 하교 후 귀가, 기본 40분 — GET·PUT /api/users/{id}/commute-time) / " +
                "MEAL(식사 — 그 요일에 등록한 BREAKFAST·LUNCH·DINNER 불가능 시간, 없으면 기본값 아침 기상 후 30분·점심 12:00~13:00·저녁 18:00~19:00. " +
                "등교일엔 학교 시간 안에 드는 아침·점심을 빼서 보통 저녁만, 쉬는 날엔 세 끼 모두) / " +
                "UNAVAILABLE(사용자가 등록한 요일별 학원·기타 불가능 시간) / TASK(모든 플랜보드의 기존 할일, taskId·planBoardId 포함). " +
                "busyTimes.label 은 앱에 보여줄 이름: 수면 / 등교 준비 / 학교 /하교 후 귀가 / 아침 식사·점심 식사·저녁 식사 / 학원·개인 일정(등록한 label 이 있으면 그것) / 할일 이름. " +
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
