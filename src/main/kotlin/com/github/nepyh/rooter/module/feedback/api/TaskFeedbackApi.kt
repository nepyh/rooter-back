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
fun TaskFeedbackApi(feedbackService: FeedbackService) = ApiRoute("plan-tasks") {
    authenticate("auth-jwt") {
        post("/{taskId}/feedback") {
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val request = call.receive<FeedbackSubmitRequest>()
            val feedback = feedbackService.submitFeedback(userId, taskId, request)
            call.respond(HttpStatusCode.Created, feedback)
        }.describe {
            tag("Feedback")
            summary = "할일 학습 피드백 설문 제출"
            description = "할일 하나에 대한 학습 난이도/소요시간/집중도 설문을 제출한다. 본인 소유 할일에만 제출 가능하고 할일당 1건만 제출 가능. " +
                "difficulty 허용값: \"쉬움\", \"적당\", \"어려움\" (이 3개 문자열 중 하나가 아니면 400). " +
                "제출 시점에는 AI 재조정을 돌리지 않는다 — 그날 제출된 설문과 오답을 모아 자정 배치가 하루 1회 실행하므로 " +
                "insertedAdjustmentTasks 는 항상 빈 배열이다"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<FeedbackSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "제출 성공 (insertedAdjustmentTasks 는 항상 빈 배열 — 조정은 자정 배치로 실행된다)"
                    ContentType.Application.Json {
                        schema = jsonSchema<FeedbackResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 taskId (code=INVALID_ID), difficulty 값 오류 (code=FEEDBACK_001, 허용값: \"쉬움\"/\"적당\"/\"어려움\"), " +
                        "timeSpentMinutes 오류 (code=FEEDBACK_002), 또는 focusLevel 범위(1~5) 오류 (code=FEEDBACK_003)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 할일 (code=PLAN_TASK_NOT_FOUND)"
                }
                HttpStatusCode.Conflict {
                    description = "해당 할일에 이미 피드백을 제출함 (code=FEEDBACK_ALREADY_SUBMITTED)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("/{taskId}/feedback") {
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val feedback = feedbackService.getFeedbackByTask(userId, taskId)
            call.respond(HttpStatusCode.OK, feedback)
        }.describe {
            tag("Feedback")
            summary = "할일 학습 피드백 설문 조회"
            description = "본인 소유 할일에 제출된 설문을 단건으로 돌려준다. 그날 제출된 목록이 필요하면 GET /daily-plans/{dailyPlanId}/feedback 을 쓴다"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<FeedbackResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 taskId (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 할일 (code=PLAN_TASK_NOT_FOUND), 또는 아직 제출된 피드백이 없음 (code=FEEDBACK_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
