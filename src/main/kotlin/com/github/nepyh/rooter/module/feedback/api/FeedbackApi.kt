package com.github.nepyh.rooter.module.feedback.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.feedback.FeedbackService
import com.github.nepyh.rooter.module.feedback.dto.FeedbackResponse
import com.github.nepyh.rooter.module.feedback.dto.FeedbackSubmitRequest
import com.github.nepyh.rooter.module.feedback.exception.DailyPlanNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackAlreadySubmittedException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackNotFoundException
import com.github.nepyh.rooter.module.feedback.exception.FeedbackValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.log
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
            try {
                val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
                if (dailyPlanId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "잘못된 dailyPlanId 입니다."))
                    return@post
                }

                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<FeedbackSubmitRequest>()
                val feedback = feedbackService.submitFeedback(userId, dailyPlanId, request)
                call.respond(HttpStatusCode.Created, feedback)
            } catch (_: DailyPlanNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to "존재하지 않거나 본인 소유가 아닌 일일 계획입니다."))
            } catch (_: FeedbackAlreadySubmittedException) {
                call.respond(HttpStatusCode.Conflict, mapOf("message" to "이미 해당 날짜의 피드백을 제출했습니다."))
            } catch (e: FeedbackValidationException.InvalidDifficultyException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "FEEDBACK_001", "message" to e.message))
            } catch (e: FeedbackValidationException.InvalidTimeSpentMinutesException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "FEEDBACK_002", "message" to e.message))
            } catch (e: FeedbackValidationException.InvalidFocusLevelException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("code" to "FEEDBACK_003", "message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("일일 피드백 제출 중 오류 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Feedback")
            summary = "일일 학습 피드백 설문 제출"
            description = "퀴즈 완료 후 당일 학습 난이도/소요시간/집중도에 대한 설문을 제출. 본인 소유의 일일 계획에만 제출 가능, 계획당 1회만 제출 가능"
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
                    description = "잘못된 dailyPlanId, difficulty 값 오류 (code=FEEDBACK_001), " +
                        "timeSpentMinutes 오류 (code=FEEDBACK_002), 또는 focusLevel 범위(1~5) 오류 (code=FEEDBACK_003)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획"
                }
                HttpStatusCode.Conflict {
                    description = "해당 일일 계획에 이미 피드백을 제출함"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("/{dailyPlanId}/feedback") {
            try {
                val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
                if (dailyPlanId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "잘못된 dailyPlanId 입니다."))
                    return@get
                }

                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val feedback = feedbackService.getFeedback(userId, dailyPlanId)
                call.respond(HttpStatusCode.OK, feedback)
            } catch (_: DailyPlanNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to "존재하지 않거나 본인 소유가 아닌 일일 계획입니다."))
            } catch (_: FeedbackNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to "아직 제출된 피드백이 없습니다."))
            } catch (e: Exception) {
                call.application.log.error("일일 피드백 조회 중 오류 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
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
                    description = "잘못된 dailyPlanId"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 일일 계획, 또는 아직 제출된 피드백이 없음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
