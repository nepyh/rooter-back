package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.dto.DailyPlanResponse
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
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
            try {
                val dateParam = call.request.queryParameters["date"]
                val date = if (dateParam != null) {
                    runCatching { LocalDate.parse(dateParam) }
                        .getOrElse { throw PlanTaskValidationException.InvalidDateParamException() }
                } else {
                    LocalDate.now()
                }

                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val dailyPlan = planTaskService.getDailyPlan(userId, date)
                call.respond(HttpStatusCode.OK, dailyPlan)
            } catch (e: PlanTaskValidationException.InvalidDateParamException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "TASK_006", "message" to e.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("PlanTask")
            summary = "일일 태스크 목록 조회"
            description = "date 파라미터(yyyy-MM-dd)로 지정한 날짜의 태스크를 조회, 생략 시 오늘 날짜. 본인 소유의 플랜보드 태스크만 조회"
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
                HttpStatusCode.BadRequest {
                    description = "date 파라미터 형식이 올바르지 않음 (code=TASK_006)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<PlanTaskCreateRequest>()
                planTaskService.createTask(userId, request)
                call.respond(HttpStatusCode.Created, mapOf("message" to "성공적으로 등록되었습니다."))
            } catch (e: PlanBoardNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (e: PlanTaskValidationException.InvalidTaskNameException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "TASK_001", "message" to e.message))
            } catch (e: PlanTaskValidationException.InvalidPlanDateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "TASK_002", "message" to e.message))
            } catch (e: PlanTaskValidationException.InvalidTimeFormatException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "TASK_003", "message" to e.message))
            } catch (e: PlanTaskValidationException.InvalidTimeRangeException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "TASK_007", "message" to e.message))
            } catch (e: PlanTaskValidationException.InvalidEstimatedMinutesException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "TASK_004", "message" to e.message))
            } catch (e: PlanTaskValidationException.PlanDateOutOfRangeException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "TASK_005", "message" to e.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("PlanTask")
            summary = "태스크 생성"
            description = "본인 소유의 플랜보드에만 태스크 생성 가능"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanTaskCreateRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 플랜보드"
                }
                HttpStatusCode.BadRequest {
                    description = "태스크 이름 오류 (code=TASK_001), 계획 날짜 형식 오류 (code=TASK_002), 시간 형식 오류 (code=TASK_003), " +
                        "예상 소요 시간 오류 (code=TASK_004), 계획 날짜가 플랜보드 기간을 벗어남 (code=TASK_005), " +
                        "또는 종료 시간이 시작 시간보다 빠르거나 같음 (code=TASK_007)"
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
