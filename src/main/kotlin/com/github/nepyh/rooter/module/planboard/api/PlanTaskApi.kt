package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.LocalDate

@OptIn(ExperimentalKtorApi::class)
fun PlanTaskApi(planTaskService: PlanTaskService) = ApiRoute("plan-tasks") {
    authenticate("auth-jwt") {
        get("") {
            val dateParam = call.request.queryParameters["date"]
            val date = if (dateParam != null) {
                runCatching { LocalDate.parse(dateParam) }
                    .getOrElse { throw PlanTaskValidationException.InvalidDateParamException() }
            } else {
                LocalDate.now()
            }

            val dailyPlan = planTaskService.getDailyPlan(call.userId(), date)
            call.respond(HttpStatusCode.OK, dailyPlan)
        }.describe {
            tag("PlanTask")
            summary = "일일 태스크 목록 조회"
            description = "date 파라미터(yyyy-MM-dd)로 지정한 날짜의 태스크를 조회, 생략 시 오늘 날짜. 본인 플랜보드 기준"
            parameters {
                query("date") {
                    description = "조회할 날짜 (yyyy-MM-dd)"
                    required = false
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<DailyPlanResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadRequest {
                    description = "date 파라미터 형식이 올바르지 않음 (code=TASK_006)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("") {
            val request = call.receive<PlanTaskCreateRequest>()
            planTaskService.createTask(call.userId(), request)
            call.respond(HttpStatusCode.Created, PlanTaskCreateResponse("성공적으로 등록되었습니다."))
        }.describe {
            tag("PlanTask")
            summary = "태스크 생성"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanTaskCreateRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않는 플랜보드"
                }
                HttpStatusCode.Forbidden {
                    description = "본인 플랜보드가 아님"
                }
                HttpStatusCode.BadRequest {
                    description = "태스크 이름 오류 (code=TASK_001), 계획 날짜 형식 오류 (code=TASK_002), 시간 형식 오류 (code=TASK_003), " +
                        "예상 소요 시간 오류 (code=TASK_004), 또는 계획 날짜가 플랜보드 기간을 벗어남 (code=TASK_005)"
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
