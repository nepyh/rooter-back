package com.github.nepyh.rooter.module.feedback.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.feedback.FeedbackService
import com.github.nepyh.rooter.module.feedback.dto.FeedbackResponse
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

@OptIn(ExperimentalKtorApi::class)
fun FeedbackApi(feedbackService: FeedbackService) = ApiRoute("daily-plans") {
    authenticate("auth-jwt") {
        get("/{dailyPlanId}/feedback") {
            val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
            if (dailyPlanId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
                return@get
            }

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val feedbacks = feedbackService.getFeedbacksByDailyPlan(userId, dailyPlanId)
            call.respond(HttpStatusCode.OK, feedbacks)
        }.describe {
            tag("Feedback")
            summary = "그날 제출된 학습 피드백 설문 목록 조회"
            description = "본인 소유의 일일 계획에 그날 제출된 피드백 설문 목록을 돌려준다 (설문은 할일 단위라 한 날에 여러 건일 수 있다). " +
                "할일 시작 시간 순으로 정렬하고, 할일이 연결되지 않은 옛 행은 뒤에 붙는다. 아직 아무 것도 제출하지 않았으면 빈 배열(404 아님). " +
                "할일 하나의 설문 단건은 GET /plan-tasks/{taskId}/feedback 으로 조회한다"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공 (제출된 설문이 없으면 빈 배열)"
                    ContentType.Application.Json {
                        schema = jsonSchema<List<FeedbackResponse>>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 dailyPlanId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획 (code=DAILY_PLAN_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
