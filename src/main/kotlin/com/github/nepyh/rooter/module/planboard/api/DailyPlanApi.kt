package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.planboard.DailyPlanService
import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
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
fun DailyPlanApi(dailyPlanService: DailyPlanService) = ApiRoute("plan-boards") {

    authenticate("auth-jwt") {
        get("/{boardId}/daily") {
            val boardId = call.parameters["boardId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_BOARD_ID", "잘못된 boardId 입니다."))

            val dateParam = call.request.queryParameters["date"]
            val date = if (dateParam != null) {
                runCatching { LocalDate.parse(dateParam) }
                    .getOrElse { throw PlanTaskValidationException.InvalidDateParamException() }
            } else {
                LocalDate.now()
            }

            val plan = dailyPlanService.getDailyPlan(call.userId(), boardId, date)
            call.respond(HttpStatusCode.OK, plan)
        }.describe {
            tag("DailyPlan")
            summary = "오늘의 스터디 플랜 조회"
            description = "본인 플랜보드만 조회 가능"
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
    }
}

private fun ApplicationCall.userId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
