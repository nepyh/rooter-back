package com.github.nepyh.rooter.module.taskquiz.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.ErrorResponse
import com.github.nepyh.rooter.module.taskquiz.QUIZ_HISTORY_PAGE_SIZE
import com.github.nepyh.rooter.module.taskquiz.QuizHistoryStatus
import com.github.nepyh.rooter.module.taskquiz.TaskQuizHistoryService
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryDetailResponse
import com.github.nepyh.rooter.module.taskquiz.dto.QuizHistoryListResponse
import com.github.nepyh.rooter.module.taskquiz.exception.QuizHistoryValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
fun TaskQuizHistoryApi(taskQuizHistoryService: TaskQuizHistoryService) = ApiRoute("quiz-history") {
    authenticate("auth-jwt") {
        get("") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val status = when (call.request.queryParameters["status"]?.lowercase()) {
                null, "" -> null
                "todo" -> QuizHistoryStatus.TODO
                "missed" -> QuizHistoryStatus.MISSED
                "done" -> QuizHistoryStatus.DONE
                else -> throw QuizHistoryValidationException.InvalidStatusException()
            }
            val cursor = call.request.queryParameters["cursor"]
                ?.let { it.toIntOrNull() ?: throw QuizHistoryValidationException.InvalidPageException() }
            val limit = call.request.queryParameters["limit"]
                ?.let { it.toIntOrNull()?.takeIf { n -> n in 1..QUIZ_HISTORY_PAGE_SIZE } ?: throw QuizHistoryValidationException.InvalidPageException() }
                ?: QUIZ_HISTORY_PAGE_SIZE

            call.respond(HttpStatusCode.OK, taskQuizHistoryService.list(userId, status, cursor, limit))
        }.describe {
            tag("QuizHistory")
            summary = "퀴즈 히스토리 목록"
            description = "본인 퀴즈를 차수 단위로 최신순으로 돌려준다. 지금은 태스크 완료 확인 퀴즈만 담고(type=TASK), 일일 퀴즈는 사양이 확정되면 같은 목록에 합류한다. " +
                "status 로 todo(오늘 풀 퀴즈)·missed(계획 날짜가 지나도록 안 푼 퀴즈)·done(응시 완료) 을 나눠 받고, 생략하면 모두 나온다 (화면이 섹션이므로 보통 섹션마다 한 번씩 부른다). " +
                "퀴즈는 할일이 끝나면 한 일·안 한 일 가리지 않고 자동 생성되므로, 안 푼 퀴즈는 계획 날짜 자정이 지나면 todo 에서 missed 로 넘어간다. missed 도 지금 풀어 제출할 수 있고 제출하면 done 이 된다. " +
                "항목에는 제목(할일 이름)·과목·시간 3종(생성/응시 예정/완료)·점수가 담긴다 — 응시 예정 시간은 할일이 끝나는 시각(퀴즈가 열리는 시각)이고, " +
                "완료 시간과 정답 수는 아직 채점 전이면 null 이다. 다음 페이지는 응답의 nextCursor 를 cursor 로 넘겨 받는다 (nextCursor=null 이면 마지막). " +
                "totalCount 는 같은 status 조건의 전체 수이고, 퀴즈가 아직 만들어지지 않은 일정은 목록에 나오지 않는다"
            parameters {
                query("status") {
                    description = "todo(오늘 풀 퀴즈) / missed(놓친 퀴즈) / done(응시 완료). 생략 시 전체"
                    required = false
                    schema = jsonSchema<String>()
                }
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
                        schema = jsonSchema<QuizHistoryListResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "status 값이 todo/missed/done 이 아님 (code=QUIZ_HISTORY_INVALID_STATUS), cursor 가 정수가 아니거나 limit 이 1~50 밖 (code=QUIZ_HISTORY_INVALID_PAGE)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }

        get("{quizId}") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
            val quizId = call.parameters["quizId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "유효하지 않은 ID입니다."))

            call.respond(HttpStatusCode.OK, taskQuizHistoryService.detail(userId, quizId))
        }.describe {
            tag("QuizHistory")
            summary = "퀴즈 히스토리 상세"
            description = "목록에서 받은 quizId(차수 id) 하나의 문항별 내용을 돌려준다. 제출이 끝난 차수는 내가 고른 보기·정답·문항 풀이(questions[].explanation)·오답 보기별 틀린 이유(choices[].reason, 정답 보기는 null)를 채워서 주고, " +
                "아직 채점 전(todo·missed) 차수는 정답·풀이·이유를 null 로 숨긴다 (퀴즈를 풀기 전에 답이 새지 않게). 조회 전용이라 제출·채점·복습 배치가 일어나지 않는다"
            parameters {
                path("quizId") {
                    description = "퀴즈 차수 ID (목록 응답의 quizId)"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "조회 성공"
                    ContentType.Application.Json {
                        schema = jsonSchema<QuizHistoryDetailResponse>()
                    }
                }
                HttpStatusCode.BadRequest {
                    description = "quizId 가 정수가 아님 (code=INVALID_ID)"
                }
                HttpStatusCode.Unauthorized {
                    description = "인증되지 않음"
                }
                HttpStatusCode.NotFound {
                    description = "존재하지 않거나 본인 소유가 아닌 차수 (code=QUIZ_HISTORY_NOT_FOUND)"
                }
                HttpStatusCode.InternalServerError {
                    description = "서버 오류"
                }
            }
        }
    }
}
