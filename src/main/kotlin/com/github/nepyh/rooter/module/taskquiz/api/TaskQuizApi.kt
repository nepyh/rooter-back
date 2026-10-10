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

            val response = taskQuizService.openQuiz(userId, taskId)
            call.respond(HttpStatusCode.OK, response)
        }.describe {
            tag("TaskQuiz")
            summary = "태스크 완료 확인 퀴즈 조회"
            description = "퀴즈 열기 (앱의 완료 버튼). 풀 퀴즈를 돌려줌 — 퀴즈를 통과해야 태스크가 완료됨. 문항 수는 할일 길이(estimatedMinutes)에 따라 30분 이하 4문항, 1시간 이하 5문항, 1시간 초과 7문항이고, 통과에 필요한 정답 수는 passCount (70점 이상: 4문항 3개, 5문항 4개, 7문항 5개). " +
                "아직 퀴즈가 없으면 종료 시각을 기다리지 않고 지금 만들고(AI 생성이라 몇 초 걸림), " +
                "직전 차수 불합격 후 10분이 지났으면 다음 차수를 바로 만듦. 그 외에는 가장 최근 차수(최초 또는 재시도)를 그대로 반환. " +
                "종료 시각이 지나면 열지 않아도 기존처럼 자동 생성됨. 이미 답한 문제는 selectedChoiceId 가 채워져 있어 앱을 다시 켜도 이어서 풀 수 있음"
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
                    description = "존재하지 않거나 본인 소유가 아닌 태스크 (code=TASK_QUIZ_NOT_FOUND)"
                }
                HttpStatusCode.BadGateway {
                    description = "AI 퀴즈 생성 실패 (code=TASK_QUIZ_GENERATION_FAILED) — 잠시 후 다시 열면 됨"
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
                "70점 이상(passCount 개 이상 정답)이면 통과로, 해당 태스크가 자동으로 완료 처리됨(passed=true). " +
                "그보다 적으면 10분 뒤 새 문제로 재시도가 자동 생성되고(retryScheduled=true), " +
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
