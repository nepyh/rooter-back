package com.github.nepyh.rooter.module.taskquiz.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizSubmitRequest
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizSubmitResponse
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizNotFoundException
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizValidationException
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
fun TaskQuizApi(taskQuizService: TaskQuizService) = ApiRoute("plan-tasks") {
    authenticate("auth-jwt") {
        get("{taskId}/quiz") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val taskId = call.parameters["taskId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))

                val response = taskQuizService.getCurrentQuiz(userId, taskId)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: TaskQuizNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("태스크 완료 퀴즈 조회 중 예외 발생 (taskId=${call.parameters["taskId"]})", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("TaskQuiz")
            summary = "태스크 완료 확인 퀴즈 조회"
            description = "태스크 종료 시각이 지나면 완료 처리 여부와 상관없이 자동 생성되는 퀴즈(5문항)를 조회. 가장 최근 시도(최초 또는 재시도)를 반환"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<TaskQuizResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "아직 생성되지 않았거나 본인 소유가 아닌 태스크"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{taskId}/quiz/submit") {
            try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val taskId = call.parameters["taskId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
                val request = call.receive<TaskQuizSubmitRequest>()

                val response = taskQuizService.submitQuiz(userId, taskId, request.answers)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: TaskQuizNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
            } catch (e: TaskQuizValidationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
            } catch (e: Exception) {
                call.application.log.error("태스크 완료 퀴즈 제출 중 예외 발생 (taskId=${call.parameters["taskId"]})", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
            }
        }.describe {
            tag("TaskQuiz")
            summary = "태스크 완료 확인 퀴즈 제출"
            description = "퀴즈 자체가 완료 확인 수단: 4개 이상 정답(통과)이면 해당 태스크가 자동으로 완료 처리됨(passed=true). " +
                "4개 미만이면 10분 뒤 새 문제로 재시도가 자동 생성되고(retryScheduled=true), " +
                "최초 1회 + 재시도 2회 모두 실패하면(attemptNumber=3에서 불합격) 해당 태스크가 미완료로 확정됨(taskInvalidated=true)"
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<TaskQuizSubmitRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "채점 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<TaskQuizSubmitResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "이미 채점된 퀴즈, 또는 문제 구성과 맞지 않는 답안"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "아직 생성되지 않았거나 본인 소유가 아닌 태스크"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
