package com.github.nepyh.rooter.module.leveltest.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.leveltest.LevelTestService
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestGenerateRequest
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestGenerateResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestSubmitRequest
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestSubmitResponse
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestNotFoundException
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestValidationException
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
fun LevelTestApi(levelTestService: LevelTestService) = ApiRoute("level-test") {
    authenticate("auth-jwt") {
        post("generate") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<LevelTestGenerateRequest>()

                val response = levelTestService.generateTest(userId, request.grade)
                call.respond(HttpStatusCode.Created, response)
            } catch (e: LevelTestValidationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("실력 테스트 생성 중 예외 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("LevelTest")
            summary = "실력 테스트 생성"
            description = "grade(1~3, 중학교 학년)를 받아 한 학년 아래 수준의 국어/영어/수학 통합 5문항 내외를 출제. 계획 생성과 독립적"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<LevelTestGenerateRequest>()
                }
            }
            responses {
                HttpStatusCode.Created {
                    description = "생성 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<LevelTestGenerateResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "grade 범위 오류(1~3) 또는 테스트 생성 실패"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{attemptId}/submit") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val attemptId = call.parameters["attemptId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val request = call.receive<LevelTestSubmitRequest>()

                val response = levelTestService.submitTest(userId, attemptId, request.answers)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: LevelTestNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (e: LevelTestValidationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("실력 테스트 제출 중 예외 발생 (attemptId=${call.parameters["attemptId"]})", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("LevelTest")
            summary = "실력 테스트 제출 및 채점"
            description = "채점은 서버가 직접 수행하고, 정답률로 과목별/전체 등급(상/중/하)을 산출"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<LevelTestSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "제출 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<LevelTestSubmitResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "이미 제출했거나, 문제 구성과 맞지 않는 답안"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 테스트"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
