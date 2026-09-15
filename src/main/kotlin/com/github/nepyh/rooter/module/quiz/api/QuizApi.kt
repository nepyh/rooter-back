package com.github.nepyh.rooter.module.quiz.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.quiz.QuizService
import com.github.nepyh.rooter.module.quiz.dto.QuizGenerateRequest
import com.github.nepyh.rooter.module.quiz.dto.QuizResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizResultResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizSubmitRequest
import com.github.nepyh.rooter.module.quiz.exception.QuizNotFoundException
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
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
import java.time.LocalDate

@OptIn(ExperimentalKtorApi::class)
fun QuizApi(quizService: QuizService) = ApiRoute("quiz") {
    authenticate("auth-jwt") {
        post("generate") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<QuizGenerateRequest>()
                val date = request.date
                    ?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw QuizValidationException.InvalidDateFormatException() } }
                    ?: LocalDate.now()

                val response = quizService.generateQuiz(userId, date)
                call.respond(HttpStatusCode.Created, response)
            } catch (e: QuizValidationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("퀴즈 생성 중 예외 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Quiz")
            summary = "일일 퀴즈 생성"
            description = "date(yyyy-MM-dd, 생략 시 오늘)의 완료된 학습 범위를 바탕으로 퀴즈를 생성"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<QuizGenerateRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<QuizResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "날짜 형식 오류, 해당 날짜에 계획 없음, 또는 퀴즈 생성 실패"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{dailyPlanId}") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))

                val response = quizService.getQuiz(userId, dailyPlanId)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: QuizNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("퀴즈 조회 중 예외 발생 (dailyPlanId=${call.parameters["dailyPlanId"]})", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Quiz")
            summary = "퀴즈 문제 조회"
            description = "본인 소유의 daily_plan 에 대한 퀴즈만 조회 가능, 정답은 노출되지 않음"
            parameters {
                path("dailyPlanId") {
                    description = "일일 계획 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<QuizResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 퀴즈"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{dailyPlanId}/submit") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val dailyPlanId = call.parameters["dailyPlanId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val request = call.receive<QuizSubmitRequest>()

                val response = quizService.submitQuiz(userId, dailyPlanId, request.answers)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: QuizNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (e: QuizValidationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("퀴즈 제출 중 예외 발생 (dailyPlanId=${call.parameters["dailyPlanId"]})", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("Quiz")
            summary = "퀴즈 제출 및 채점"
            description = "채점 후 오답 챕터에 대한 복습 태스크를 남은 일정에 자동 추가"
            parameters {
                path("dailyPlanId") {
                    description = "일일 계획 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<QuizSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "제출 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<QuizResultResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "이미 제출했거나, 문제 구성과 맞지 않는 답안"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 퀴즈"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
