package com.github.nepyh.rooter.module.feedback.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.feedback.FeedbackService
import com.github.nepyh.rooter.module.feedback.dto.FeedbackResponse
import com.github.nepyh.rooter.module.feedback.dto.FeedbackSubmitRequest
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

@OptIn(ExperimentalKtorApi::class)
fun FeedbackApi(feedbackService: FeedbackService) = ApiRoute("daily-plans") {
    authenticate("auth-jwt") {
        post("/{dailyPlanId}/feedback") {
            val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
            if (dailyPlanId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 dailyPlanId 입니다."))
                return@post
            }

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val request = call.receive<FeedbackSubmitRequest>()
            val feedback = feedbackService.submitFeedback(userId, dailyPlanId, request)
            call.respond(HttpStatusCode.Created, feedback)
        }.describe {
            tag("Feedback")
            summary = "일일 학습 피드백 설문 제출"
            description = "퀴즈 완료 후 당일 학습 난이도/소요시간/집중도에 대한 설문을 제출. 본인 소유의 일일 계획에만 제출 가능, 계획당 1회만 제출 가능. " +
                "difficulty 허용값: \"쉬움\", \"적당\", \"어려움\" (이 3개 문자열 중 하나가 아니면 400). " +
                "제출 직후 오늘 틀린 퀴즈 문제와 설문 응답을 근거로 AI가 남은 날짜에 보충/심화 태스크를 자동 추가한다 (insertedAdjustmentTasks, AI 호출 실패 시 빈 배열)"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<FeedbackSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "제출 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<FeedbackResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 dailyPlanId (code=INVALID_ID), difficulty 값 오류 (code=FEEDBACK_001, 허용값: \"쉬움\"/\"적당\"/\"어려움\"), " +
                        "timeSpentMinutes 오류 (code=FEEDBACK_002), 또는 focusLevel 범위(1~5) 오류 (code=FEEDBACK_003)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획 (code=DAILY_PLAN_NOT_FOUND)"
                }
                HttpStatusCode.Conflict {
                    description = "해당 일일 계획에 이미 피드백을 제출함 (code=FEEDBACK_ALREADY_SUBMITTED)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("/{dailyPlanId}/feedback") {
            val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
            if (dailyPlanId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "잘못된 dailyPlanId 입니다."))
                return@get
            }

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val feedback = feedbackService.getFeedback(userId, dailyPlanId)
            call.respond(HttpStatusCode.OK, feedback)
        }.describe {
            tag("Feedback")
            summary = "일일 학습 피드백 설문 조회"
            description = "본인 소유의 일일 계획에 제출된 피드백만 조회 가능"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<FeedbackResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "잘못된 dailyPlanId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획 (code=DAILY_PLAN_NOT_FOUND), 또는 아직 제출된 피드백이 없음 (code=FEEDBACK_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
