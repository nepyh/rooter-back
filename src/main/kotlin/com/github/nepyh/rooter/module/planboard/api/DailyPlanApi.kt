package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.DailyPlanService
import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
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
fun DailyPlanApi(dailyPlanService: DailyPlanService) = ApiRoute("plan-boards") {
    authenticate("auth-jwt") {
        get("/{boardId}/daily") {
            try {
                val boardId = call.parameters["boardId"]?.toIntOrNull()
                if (boardId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "잘못된 boardId 입니다."))
                    return@get
                }

                // date 쿼리 파라미터 (없으면 오늘)
                val dateParam = call.request.queryParameters["date"]
                val date = try {
                    if (dateParam != null) LocalDate.parse(dateParam) else LocalDate.now()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "날짜 형식이 잘못되었습니다. (예: 2026-07-23)"))
                    return@get
                }

                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val plan = dailyPlanService.getDailyPlan(userId, boardId, date)
                call.respond(HttpStatusCode.OK, plan)
            } catch (_: PlanBoardNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to "존재하지 않는 플랜보드입니다."))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("DailyPlan")
            summary = "오늘의 스터디 플랜 조회"
            description = "본인 소유의 플랜보드만 조회 가능"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공 (해당 날짜 계획이 없으면 tasks 빈 배열)"
                    ContentType.Application.Json {
                        schema = jsonSchema<DailyPlanResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 boardId 또는 날짜 형식"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 플랜보드"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
