package com.github.nepyh.rooter.module.taskquiz.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.taskquiz.TaskQuizService
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizAnswerRequest
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizAnswerResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizSubmitResponse
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
fun TaskQuizApi(taskQuizService: TaskQuizService) = ApiRoute("plan-tasks") {
    authenticate("auth-jwt") {
        get("{taskId}/quiz") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            val response = taskQuizService.getCurrentQuiz(userId, taskId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("TaskQuiz")
            summary = "태스크 완료 확인 퀴즈 조회"
            description = "완료 버튼(POST .../quiz/start)을 눌렀거나 태스크 종료 시각이 지나 만들어진 퀴즈(5문항)를 조회. 가장 최근 시도(최초 또는 재시도)를 반환. 이미 답한 문제는 selectedChoiceId 가 채워져 있어 앱을 다시 켜도 이어서 풀 수 있음"
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<TaskQuizResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "아직 생성되지 않았거나 본인 소유가 아닌 태스크 (code=TASK_QUIZ_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{taskId}/quiz/start") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            call.respond(HttpStatusCode.OK, taskQuizService.startQuiz(userId, taskId))
        }.describe {
            tag("TaskQuiz")
            summary = "완료 버튼 — 태스크 완료 확인 퀴즈 바로 시작"
            description = "앱의 완료 버튼에서 호출. 풀 수 있는 퀴즈를 바로 돌려줌 (응답은 GET .../quiz 와 같음). 퀴즈를 통과해야 태스크가 완료됨. " +
                "아직 퀴즈가 없으면 종료 시각을 기다리지 않고 지금 1차 퀴즈를 만들고(AI 생성이라 몇 초 걸림), 풀고 있는 퀴즈가 있으면 그대로 돌려줌. " +
                "직전 차수에서 떨어졌으면 10분 대기 후에만 다음 차수를 만듦 (최초 1회 + 재시도 2회). " +
                "완료 버튼을 누르지 않아도 종료 시각이 지나면 퀴즈는 기존처럼 자동으로 만들어짐"
            responses {
                HttpStatusCode.OK {
                    description = "풀 퀴즈"
                    ContentType.Application.Json {
                        schema = jsonSchema<TaskQuizResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 이미 퀴즈를 통과함 (code=TASK_QUIZ_ALREADY_PASSED), " +
                        "3번 모두 불합격 (code=TASK_QUIZ_NO_MORE_ATTEMPTS), 재시도 대기 중 (code=TASK_QUIZ_RETRY_NOT_READY, message 에 남은 분)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 태스크 (code=TASK_QUIZ_NOT_FOUND)"
                }
                HttpStatusCode.BadGateway {
                    description = "AI 퀴즈 생성 실패 (code=TASK_QUIZ_GENERATION_FAILED) — 잠시 후 다시 호출"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{taskId}/quiz/questions/{questionId}/answer") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val questionId = call.parameters["questionId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))
            val request = call.receive<TaskQuizAnswerRequest>()

            val response = taskQuizService.answerQuestion(userId, taskId, questionId, request.selectedChoiceId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("TaskQuiz")
            summary = "퀴즈 문제 하나 답변 (즉시 채점)"
            description = "문제를 풀 때마다(앱에서 '다음'을 누를 때) 호출. 답을 서버에 저장하고 정답 여부·정답을 바로 돌려줌. " +
                "틀리면 reason 에 고른 보기가 왜 틀렸는지 한두 문장이 들어감(맞으면 null). explanation 에는 맞든 틀리든 이 문제의 자세한 풀이가 들어감 " +
                "(제출 응답 results[].explanation 과 같은 값). " +
                "한 번 답한 문제는 다시 답할 수 없음 — 정답을 본 뒤 답을 바꿔치기하는 것을 막기 위함. " +
                "최종 채점(POST .../quiz/submit)은 여기서 저장된 답만 보고, 앱이 별도로 보내는 답은 받지 않음"
            parameters {
                path("taskId") {
                    description = "태스크 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
                path("questionId") {
                    description = "문제 ID"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            requestBody {
                ContentType.Application.Json {
                    schema = jsonSchema<TaskQuizAnswerRequest>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "채점 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<TaskQuizAnswerResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 이미 채점이 끝난 퀴즈 (code=TASK_QUIZ_ALREADY_SUBMITTED), " +
                        "이미 답한 문제 (code=TASK_QUIZ_QUESTION_ALREADY_ANSWERED), 또는 문제 구성과 맞지 않는 답안 (code=TASK_QUIZ_INVALID_ANSWER)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "아직 생성되지 않았거나 본인 소유가 아닌 태스크, 또는 존재하지 않는 문제 (code=TASK_QUIZ_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        post("{taskId}/quiz/submit") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val taskId = call.parameters["taskId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            val response = taskQuizService.submitQuiz(userId, taskId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("TaskQuiz")
            summary = "태스크 완료 확인 퀴즈 제출"
            description = "요청 본문 없음 — 채점은 항상 POST .../quiz/questions/{questionId}/answer 로 저장해 둔 답만 본다. " +
                "4개 이상 정답(통과)이면 해당 태스크가 자동으로 완료 처리됨(passed=true). " +
                "4개 미만이면 10분 뒤 새 문제로 재시도가 자동 생성되고(retryScheduled=true), " +
                "최초 1회 + 재시도 2회 모두 실패하면(attemptNumber=3에서 불합격) 해당 태스크가 미완료로 확정됨(taskInvalidated=true). 재시도가 잡히면(retryScheduled=true) 오늘 남은(아직 시작 안 한) 태스크를 15분 뒤로 밀고 shiftedTasks 로 돌려줌 (학원 등 불가능 시간은 건너뛰고, 23시를 넘어도 그날 안에 둠)"
            responses {
                HttpStatusCode.OK {
                    description = "채점 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<TaskQuizSubmitResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "유효하지 않은 ID (code=INVALID_ID), 이미 채점된 퀴즈 (code=TASK_QUIZ_ALREADY_SUBMITTED), " +
                        "또는 아직 답하지 않은 문제가 있음 (code=TASK_QUIZ_INCOMPLETE_ANSWERS)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "아직 생성되지 않았거나 본인 소유가 아닌 태스크 (code=TASK_QUIZ_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
