package com.github.nepyh.rooter.module.planboard.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.planboard.PlanGenerationService
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationResponse
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun PlanGenerationApi(planGenerationService: PlanGenerationService) = ApiRoute("plan-generation") {
    authenticate("auth-jwt") {
        post("") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<PlanGenerationRequest>()

                val response = planGenerationService.generate(userId, request)
                call.respond(HttpStatusCode.Created, response)
            } catch (e: PlanBoardValidationException) {
                call.respond(e.status, mapOf("code" to e.code, "message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("AI 학습 계획 생성 중 예외 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("PlanGeneration")
            summary = "AI 학습 계획 생성"
            description = "사용자가 직접 지정한 교과서/단원 범위(subjects)를 바탕으로 AI가 시험일까지의 일일 학습 계획을 생성. " +
                "plan_board, plan_subjects, daily_plans, plan_tasks 를 한 번에 생성. 문서 업로드 없이 이미 DB에 있는 교과서/단원 데이터만 사용"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<PlanGenerationRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<PlanGenerationResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "제목/날짜/과목 범위 오류 (code: INVALID_TITLE, INVALID_DATE_FORMAT, INVALID_DATE_RANGE, " +
                        "SUBJECTS_REQUIRED, INVALID_SUBJECT_RANGE, MISSING_DATE_INFO)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.BadGateway {
                    description = "AI 계획 생성 실패 (code: GENERATION_FAILED)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
