package com.github.nepyh.rooter.module.studystyle.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.studystyle.StudyStyleService
import com.github.nepyh.rooter.module.studystyle.dto.StudyStyleResponse
import com.github.nepyh.rooter.module.studystyle.dto.StudyStyleSubmitRequest
import com.github.nepyh.rooter.module.studystyle.exception.StudyStyleValidationException
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
fun StudyStyleApi(studyStyleService: StudyStyleService) = ApiRoute("study-style") {
    authenticate("auth-jwt") {
        post("") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<StudyStyleSubmitRequest>()

                val response = studyStyleService.submitAnswers(userId, request)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: StudyStyleValidationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("공부스타일 설문 제출 중 예외 발생", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("StudyStyle")
            summary = "공부스타일 설문 제출"
            description = "questionNumber(1~7)별 answerOption(1~3, 4=모르겠어요)을 제출. 제출한 문항만 기존 답변을 덮어씀, 전체 재제출 불필요"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<StudyStyleSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "제출 성공, 현재까지 저장된 전체 답변 반환"
                    ContentType.Application.Json {
                        schema = jsonSchema<StudyStyleResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "answers가 비어있거나, questionNumber/answerOption 범위 오류, 또는 같은 문항 중복 제출"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val response = studyStyleService.getAnswers(userId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("StudyStyle")
            summary = "공부스타일 설문 응답 조회"
            description = "본인이 제출한 답변만 조회 (미응답 문항은 목록에서 생략됨)"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<StudyStyleResponse>()
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
    }
}
