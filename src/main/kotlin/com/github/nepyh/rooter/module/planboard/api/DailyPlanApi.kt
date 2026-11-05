package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.WeeklyPlanResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
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

@OptIn(ExperimentalKtorApi::class)
fun DailyPlanApi(planTaskService: PlanTaskService) = ApiRoute("plan-boards") {

    authenticate("auth-jwt") {
        get("/{boardId}/daily") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_BOARD_ID", "잘못된 boardId 입니다."))

            val dateParam = call.request.queryParameters["date"]
            val date = if (dateParam != null) {
                runCatching { LocalDate.parse(dateParam) }
                    .getOrElse { throw PlanTaskValidationException.InvalidDateParamException() }
            } else {
                todayInAppZone()
            }

            val plan = planTaskService.getBoardDailyPlan(call.userId(), boardId, date)
            call.respond(HttpStatusCode.OK, plan)
        }.describe {
            tag("DailyPlan")
            summary = "오늘의 스터디 플랜 조회"
            description = "본인 플랜보드만 조회 가능. 응답 dailyPlanId 로 피드백·챗봇(/daily-plans/{dailyPlanId}/...)을 호출 (해당 날짜 일일 계획이 없으면 null)"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공 (해당 날짜 계획이 없으면 tasks 빈 배열)"
                    ContentType.Application.Json {
                        schema = jsonSchema<DailyPlanResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 boardId 또는 날짜 형식"
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
        get("/{boardId}/weekly") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_BOARD_ID", "잘못된 boardId 입니다."))

            val dateParam = call.request.queryParameters["date"]
            val date = if (dateParam != null) {
                runCatching { LocalDate.parse(dateParam) }
                    .getOrElse { throw PlanTaskValidationException.InvalidDateParamException() }
            } else {
                todayInAppZone()
            }

            val plan = planTaskService.getBoardWeeklyPlan(call.userId(), boardId, date)
            call.respond(HttpStatusCode.OK, plan)
        }.describe {
            tag("DailyPlan")
            summary = "플랜보드 주간(월~일) 플랜 조회"
            description = "본인 플랜보드만 조회 가능. date 가 속한 주(월~일)의 그 보드 할일만 돌려주고, 그 보드의 일일 계획이 있는 날은 " +
                "dailyPlanId 도 채운다 (피드백·챗봇 호출용). 여러 보드를 합치는 GET /plan-tasks/week 와 달리 다른 보드 할일은 섞이지 않는다"
            parameters {
                path("boardId") {
                    description = "플랜보드 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
                query("date") {
                    description = "이 날짜가 속한 주를 조회 (yyyy-MM-dd). 생략하면 오늘(한국 시간)"
                    required = false
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공 (그 주에 일일 계획이 없는 날은 tasks 빈 배열, dailyPlanId null)"
                    ContentType.Application.Json {
                        schema = jsonSchema<WeeklyPlanResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 boardId 또는 날짜 형식 (code=INVALID_DATE_PARAM)"
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

private fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
