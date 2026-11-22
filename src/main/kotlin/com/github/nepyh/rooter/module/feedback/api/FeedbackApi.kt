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
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val request = call.receive<FeedbackSubmitRequest>()
            val feedback = feedbackService.submitDailyFeedback(userId, dailyPlanId, request)
            call.respond(HttpStatusCode.Created, feedback)
        }.describe {
            tag("Feedback")
            summary = "오늘 공부 설문(일일 설문) 제출"
            description = "하루 한 번 내는 오늘 공부 설문(난이도/공부한 시간/집중도)을 제출한다. 본인 소유 일일 계획에만, 일일 계획당 1건만 제출 가능. " +
                "할일과 묶이지 않아 응답의 taskId 는 null 이다 — GET /daily-plans/{dailyPlanId}/feedback 목록에서 taskId 가 null 인 행이 있으면 그날 설문을 낸 것이다. " +
                "difficulty 허용값: \"쉬움\", \"적당\", \"어려움\". " +
                "제출 시점에는 AI 재조정을 돌리지 않는다 — 자정 배치가 그날 설문(일일·할일)과 오답을 모아 하루 1회 실행하므로 insertedAdjustmentTasks 는 항상 빈 배열이다"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<FeedbackSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "제출 성공 (taskId 는 null, insertedAdjustmentTasks 는 항상 빈 배열)"
                    ContentType.Application.Json {
                        schema = jsonSchema<FeedbackResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 dailyPlanId (code=INVALID_ID), difficulty 값 오류 (code=FEEDBACK_001, 허용값: \"쉬움\"/\"적당\"/\"어려움\"), " +
                        "timeSpentMinutes 오류 (code=FEEDBACK_002), 또는 focusLevel 범위(1~5) 오류 (code=FEEDBACK_003)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획 (code=DAILY_PLAN_NOT_FOUND)"
                }
                HttpStatusCode.Conflict {
                    description = "그날 이미 일일 설문을 제출함 (code=FEEDBACK_ALREADY_SUBMITTED)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

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
            description = "본인 소유의 일일 계획에 그날 제출된 피드백 설문 목록을 돌려준다 (일일 설문 최대 1건 + 할일 설문 여러 건). " +
                "taskId 가 null 인 행이 일일 설문(POST /daily-plans/{dailyPlanId}/feedback)이다. " +
                "할일 설문은 할일 시작 시간 순으로 정렬하고, 일일 설문(할일이 연결되지 않은 행)은 뒤에 붙는다. 아직 아무 것도 제출하지 않았으면 빈 배열(404 아님). " +
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
