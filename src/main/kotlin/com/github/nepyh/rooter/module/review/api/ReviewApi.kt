package com.github.nepyh.rooter.module.review.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.review.REVIEW_PAGE_SIZE
import com.github.nepyh.rooter.module.review.ReviewService
import com.github.nepyh.rooter.module.review.dto.ReviewAnswerRequest
import com.github.nepyh.rooter.module.review.dto.ReviewAnswerResponse
import com.github.nepyh.rooter.module.review.dto.ReviewQuestionListResponse
import com.github.nepyh.rooter.module.review.dto.ReviewReportResponse
import com.github.nepyh.rooter.module.review.exception.ReviewValidationException
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
fun ReviewApi(reviewService: ReviewService) = ApiRoute("review-questions") {
    authenticate("auth-jwt") {
        get("") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val cursor = call.request.queryParameters["cursor"]
                ?.let { it.toIntOrNull() ?: throw ReviewValidationException.InvalidPageException() }
            val limit = call.request.queryParameters["limit"]
                ?.let { it.toIntOrNull()?.takeIf { n -> n in 1..REVIEW_PAGE_SIZE } ?: throw ReviewValidationException.InvalidPageException() }
                ?: REVIEW_PAGE_SIZE

            call.respond(HttpStatusCode.OK, reviewService.listOpen(userId, cursor, limit))
        }.describe {
            tag("Review")
            summary = "복습 문제 목록"
            description = "일일·태스크 퀴즈에서 틀린 문제(틀리는 즉시 들어옴)와 약점 맞춤 문제 중 아직 맞히지 않은 것을 최근 것부터 반환. " +
                "보기 순서는 원래 퀴즈와 다르게 섞여 있고 정답 여부는 노출하지 않음. " +
                "다음 페이지는 응답의 nextCursor 를 cursor 로 넘겨 받음 (nextCursor=null 이면 마지막). totalCount 는 남은 복습 문제 전체 수"
            parameters {
                query("cursor") {
                    description = "앞 페이지 응답의 nextCursor. 생략하면 첫 페이지"
                    required = false
                    schema = jsonSchema<Int>()
                }
                query("limit") {
                    description = "한 번에 받을 개수 (1~50, 생략 시 50)"
                    required = false
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<ReviewQuestionListResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "cursor 가 정수가 아니거나 limit 이 1~50 밖 (code=REVIEW_INVALID_PAGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("report") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            call.respond(HttpStatusCode.OK, reviewService.chapterReport(userId))
        }.describe {
            tag("Review")
            summary = "단원별 오답률 리포트"
            description = "일일 퀴즈 제출과 태스크 퀴즈 답변을 문항의 단원으로 묶어 오답률(틀린 수 ÷ 푼 수, % 반올림)을 오답률 높은 단원부터 반환. " +
                "openReviewCount 는 그 단원에서 아직 맞히지 않은 복습 문제 수. 복습에서 다시 푼 기록은 오답률에 넣지 않음. " +
                "단원을 알 수 없는 문항(단원 기록 기능 전에 푼 퀴즈, 학습 범위 밖 문항)은 chapters 에서 빠지고 untaggedAnsweredCount 로만 셈. " +
                "푼 퀴즈가 없으면 chapters 는 빈 배열"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<ReviewReportResponse>()
                    }
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{reviewQuestionId}/answer") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val reviewQuestionId = call.parameters["reviewQuestionId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val request = call.receive<ReviewAnswerRequest>()

            call.respond(HttpStatusCode.OK, reviewService.answer(userId, reviewQuestionId, request.selectedChoiceId))
        }.describe {
            tag("Review")
            summary = "복습 문제 답하기 (즉시 채점)"
            description = "맞히면 복습 목록에서 바로 빠짐(cleared=true). 틀리면 목록에 그대로 남아 다시 풀 수 있고 wrongCount 가 1 늘어남. " +
                "맞든 틀리든 정답 보기(correctChoiceId)와 풀이(explanation)를 돌려주고, 틀리면 고른 보기의 짧은 이유(reason)도 줌"
            parameters {
                path("reviewQuestionId") {
                    description = "복습 문제 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<ReviewAnswerRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "채점 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<ReviewAnswerResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 이미 맞혀서 목록에서 빠진 문제 (code=REVIEW_ALREADY_CLEARED), " +
                        "또는 이 문제의 보기가 아님 (code=REVIEW_INVALID_ANSWER)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 복습 문제 (code=REVIEW_QUESTION_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
